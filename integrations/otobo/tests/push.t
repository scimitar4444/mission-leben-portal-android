# Run after concatenating MissionLebenPush.pm to this file, inside OTOBO's
# runtime. All OTOBO objects and HTTP calls are synthetic; no real tickets.
package main;
use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

package MockConfig;
sub Get { return $_[0]->{$_[1]}; }
package MockScheduler;
sub TaskAdd { my ($Self, %Args) = @_; push @{$Self->{tasks}}, \%Args; return 1; }
package MockTicket;
sub TicketGet { return %{$_[0]->{ticket}}; }
sub CustomerPermission { $_[0]->{permission}; }
package MockArticle;
sub BackendForArticle { return $_[0]->{exists} ? $_[0] : undef; }
sub ArticleGet { return %{$_[0]->{article}}; }
package MockHTML;
sub ToAscii { my ($Self, %Args) = @_; $Args{String} =~ s/<[^>]+>//g; return $Args{String}; }
package MockLog;
sub Log { my ($Self, %Args) = @_; push @{$Self->{entries}}, $Args{Message}; return 1; }
package MockOM;
sub Get { return $_[0]->{objects}->{$_[1]}; }
package Kernel::System::Ticket::Event::MissionLebenPush;
our ($Captured, $HTTPStatus, $HTTPCalls);
{ no warnings 'redefine';
sub _Post {
    my ($Self, $URL, $Body, $Headers) = @_;
    $HTTPCalls++;
    $Captured = {url => $URL, body => $Body, headers => $Headers};
    return {status => $HTTPStatus};
} }
package main;
my ($Handle, $Path) = tempfile(UNLINK => 1);
print {$Handle} 'x' x 32;
close $Handle;
my $Config = bless {
    'MissionLebenPush::Enabled' => 1,
    'MissionLebenPush::SecretFile' => $Path,
    'MissionLebenPush::URL' => 'https://id.mission-leben.de/device-bridge/sources/otobo',
}, 'MockConfig';
my $Ticket = bless {ticket => {TicketID => 42, TicketNumber => '2026101201', CustomerUserID => 'USER@example.invalid', Title => 'Drucker'}, permission => 1}, 'MockTicket';
my $Article = bless {exists => 1, article => {TicketID => 42, ArticleID => 84, SenderType => 'agent', IsVisibleForCustomer => 1, Body => 'Bitte neu starten', ContentType => 'text/plain'}}, 'MockArticle';
my $Scheduler = bless {tasks => []}, 'MockScheduler';
my $Log = bless {entries => []}, 'MockLog';
$Kernel::OM = bless {objects => {
    'Kernel::Config' => $Config, 'Kernel::System::Ticket' => $Ticket,
    'Kernel::System::Ticket::Article' => $Article, 'Kernel::System::Scheduler' => $Scheduler,
    'Kernel::System::HTMLUtils' => bless({}, 'MockHTML'), 'Kernel::System::Log' => $Log,
}}, 'MockOM';
my $Push = Kernel::System::Ticket::Event::MissionLebenPush->new();
$Kernel::System::Ticket::Event::MissionLebenPush::HTTPStatus = 202;
$Kernel::System::Ticket::Event::MissionLebenPush::HTTPCalls = 0;
ok($Push->Run(Event => 'ArticleCreate', Data => {TicketID => 42, ArticleID => 84}), 'queues article event');
is(scalar @{$Scheduler->{tasks}}, 1, 'one durable task');
is($Kernel::System::Ticket::Event::MissionLebenPush::HTTPCalls, 0, 'no network inside article transaction');
is_deeply($Scheduler->{tasks}->[0]->{Data}->{Params}, {TicketID => 42, ArticleID => 84, Retry => 0}, 'queue contains no text, secrets or customer identity');
ok($Push->Deliver(TicketID => 42, ArticleID => 84), 'visible agent reply accepted');
my $Posted = $Kernel::System::Ticket::Event::MissionLebenPush::Captured;
my $Payload = JSON::PP->new->utf8->decode($Posted->{body});
is($Payload->{recipient_login}, 'user@example.invalid', 'only current ticket customer');
is($Payload->{article_id}, '84', 'exact reply');
is($Posted->{headers}->{'X-ML-Source'}, 'otobo', 'dedicated source');
like($Posted->{headers}->{'X-ML-Signature'}, qr/\A[A-Za-z0-9_-]{43}\z/, 'SHA256 HMAC base64url');
for my $Case (['customer', 1], ['system', 1], ['agent', 0]) {
    ($Article->{article}->{SenderType}, $Article->{article}->{IsVisibleForCustomer}) = @$Case;
    ok($Push->Deliver(TicketID => 42, ArticleID => 84), 'non-eligible article skipped');
}
is($Kernel::System::Ticket::Event::MissionLebenPush::HTTPCalls, 1, 'no own replies, system articles or internal notes sent');
$Article->{article}->{SenderType} = 'agent';
$Article->{article}->{IsVisibleForCustomer} = 1;
$Ticket->{permission} = 0;
$Push->Deliver(TicketID => 42, ArticleID => 84);
is($Kernel::System::Ticket::Event::MissionLebenPush::HTTPCalls, 1, 'revoked customer access not sent');
$Ticket->{permission} = 1;
$Ticket->{ticket}->{CustomerUserID} = 'new-owner@example.invalid';
$Article->{article}->{Body} = '<p>Neue Antwort</p>';
$Article->{article}->{ContentType} = 'text/html';
$Push->Deliver(TicketID => 42, ArticleID => 84);
$Payload = JSON::PP->new->utf8->decode($Kernel::System::Ticket::Event::MissionLebenPush::Captured->{body});
is($Payload->{recipient_login}, 'new-owner@example.invalid', 'current owner re-resolved at delivery');
is($Payload->{preview}, 'Neue Antwort', 'HTML stripped before preview');
$Kernel::System::Ticket::Event::MissionLebenPush::HTTPStatus = 503;
$Push->Deliver(TicketID => 42, ArticleID => 84);
is($Scheduler->{tasks}->[-1]->{Data}->{Params}->{Retry}, 1, 'failed HTTP queued for bounded retry');
ok($Scheduler->{tasks}->[-1]->{ExecutionTime}, 'retry delayed, no polling loop');
my $Count = scalar @{$Scheduler->{tasks}};
$Push->Deliver(TicketID => 42, ArticleID => 84, Retry => 6);
is(scalar @{$Scheduler->{tasks}}, $Count, 'retry limit respected');
is(scalar @{$Log->{entries}}, 1, 'exhaustion logged without payload');
$Config->{'MissionLebenPush::Enabled'} = 0;
my $Calls = $Kernel::System::Ticket::Event::MissionLebenPush::HTTPCalls;
$Push->Deliver(TicketID => 42, ArticleID => 84);
$Push->Run(Event => 'ArticleCreate', Data => {TicketID => 42, ArticleID => 84});
is(scalar @{$Scheduler->{tasks}}, $Count, 'disable switch stops new jobs');
is($Kernel::System::Ticket::Event::MissionLebenPush::HTTPCalls, $Calls, 'disable switch also stops queued jobs');
done_testing();
