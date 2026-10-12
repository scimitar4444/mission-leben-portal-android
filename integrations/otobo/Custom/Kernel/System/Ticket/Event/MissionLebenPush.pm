package Kernel::System::Ticket::Event::MissionLebenPush;

use v5.24;
use strict;
use warnings;
use Digest::SHA qw(sha256_hex hmac_sha256);
use MIME::Base64 qw(encode_base64);
use JSON::PP ();
use HTTP::Tiny ();
use POSIX qw(strftime);

our @ObjectDependencies = (
    'Kernel::Config', 'Kernel::System::Ticket', 'Kernel::System::Ticket::Article',
    'Kernel::System::Scheduler', 'Kernel::System::HTMLUtils', 'Kernel::System::Log',
);

sub new { return bless {}, shift; }

# No HTTP calls or article bodies in the web request/queue. OTOBO's existing
# durable scheduler executes the job after the article transaction commits.
sub Run {
    my ($Self, %Param) = @_;
    return 1 if !$Kernel::OM->Get('Kernel::Config')->Get('MissionLebenPush::Enabled');
    return 1 if ($Param{Event} // '') ne 'ArticleCreate';
    my $Data = $Param{Data} // {};
    return 1 if !_ID($Data->{TicketID}) || !_ID($Data->{ArticleID});
    return $Self->_Queue($Data->{TicketID}, $Data->{ArticleID}, 0);
}

sub _Queue {
    my ($Self, $TicketID, $ArticleID, $Retry) = @_;
    my %Delay = $Retry ? (ExecutionTime => strftime('%Y-%m-%d %H:%M:%S', localtime(time + 30 * 2 ** $Retry))) : ();
    return $Kernel::OM->Get('Kernel::System::Scheduler')->TaskAdd(
        Type => 'AsynchronousExecutor', Name => "MLPush-$TicketID-$ArticleID-$Retry",
        Attempts => 3, MaximumParallelInstances => 1, %Delay,
        Data => {
            Object => __PACKAGE__, Function => 'Deliver',
            Params => {TicketID => $TicketID, ArticleID => $ArticleID, Retry => $Retry},
        },
    );
}

sub Deliver {
    my ($Self, %Param) = @_;
    my $Config = $Kernel::OM->Get('Kernel::Config');
    return 1 if !$Config->Get('MissionLebenPush::Enabled');
    return 1 if !_ID($Param{TicketID}) || !_ID($Param{ArticleID});
    my $TicketObject = $Kernel::OM->Get('Kernel::System::Ticket');
    my %Ticket = $TicketObject->TicketGet(TicketID => $Param{TicketID}, UserID => 1, DynamicFields => 0);
    return 1 if !%Ticket || !$Ticket{CustomerUserID};
    my $Backend = $Kernel::OM->Get('Kernel::System::Ticket::Article')->BackendForArticle(
        TicketID => $Param{TicketID}, ArticleID => $Param{ArticleID},
    );
    return 1 if !$Backend;
    my %Article = $Backend->ArticleGet(TicketID => $Param{TicketID}, ArticleID => $Param{ArticleID}, DynamicFields => 0);
    # Only the ticket's customer, never company contacts, CC lists, or watchers.
    return 1 if ($Article{SenderType} // '') ne 'agent' || !$Article{IsVisibleForCustomer};
    return 1 if ($Article{TicketID} // 0) != $Param{TicketID};
    return 1 if !$TicketObject->CustomerPermission(
        Type => 'ro', TicketID => $Param{TicketID}, UserID => $Ticket{CustomerUserID},
    );
    my $Preview = $Article{Body} // '';
    $Preview = $Kernel::OM->Get('Kernel::System::HTMLUtils')->ToAscii(String => $Preview)
        if ($Article{ContentType} // $Article{MimeType} // '') =~ m{text/html}i;
    my $Payload = {
        recipient_login => lc $Ticket{CustomerUserID},
        ticket_id => "$Param{TicketID}", article_id => "$Param{ArticleID}",
        ticket_number => _Text($Ticket{TicketNumber}, 32),
        title => _Text($Ticket{Title}, 120), preview => _Text($Preview, 280),
    };
    my $SecretFile = $Config->Get('MissionLebenPush::SecretFile') // '';
    my $URL = $Config->Get('MissionLebenPush::URL') // '';
    # A dedicated fixed destination; no secrets in SysConfig, queue or logs.
    return $Self->_Failed(%Param) if $URL ne 'https://id.mission-leben.de/device-bridge/sources/otobo';
    open my $Handle, '<', $SecretFile or return $Self->_Failed(%Param);
    local $/;
    my $Secret = <$Handle>;
    close $Handle;
    $Secret =~ s/\s+\z//;
    return $Self->_Failed(%Param) if length($Secret) < 32;
    my $Body = JSON::PP->new->canonical->utf8->encode($Payload);
    my $Timestamp = '' . time;
    my $Signature = encode_base64(hmac_sha256(join("\n", 'ML-SOURCE-V1', 'otobo', $Timestamp, sha256_hex($Body)), $Secret), '');
    $Signature =~ tr{+/}{-_};
    $Signature =~ s/=+\z//;
    my $Response = eval { $Self->_Post($URL, $Body, {
        'Content-Type' => 'application/json; charset=utf-8',
        'X-ML-Source' => 'otobo', 'X-ML-Timestamp' => $Timestamp, 'X-ML-Signature' => $Signature,
    }) };
    return 1 if $Response && $Response->{status} == 202;
    return $Self->_Failed(%Param);
}

sub _Post {
    my ($Self, $URL, $Body, $Headers) = @_;
    return HTTP::Tiny->new(timeout => 8, verify_SSL => 1, max_redirect => 0, max_size => 2048)
        ->post($URL, {headers => $Headers, content => $Body});
}

sub _Failed {
    my ($Self, %Param) = @_;
    my $Retry = int($Param{Retry} // 0);
    if ($Retry < 6) {
        return $Self->_Queue($Param{TicketID}, $Param{ArticleID}, $Retry + 1);
    }
    $Kernel::OM->Get('Kernel::System::Log')->Log(Priority => 'error',
        Message => 'MissionLebenPush delivery exhausted; inspect Bridge and assignment-export health.');
    return 1;
}

sub _ID { return defined $_[0] && $_[0] =~ /\A[1-9][0-9]{0,19}\z/; }
sub _Text {
    my ($Text, $Maximum) = @_;
    $Text //= '';
    $Text =~ s/[\x00-\x1f\x7f]+/ /g;
    $Text =~ s/\s+/ /g;
    $Text =~ s/^\s+|\s+$//g;
    return substr($Text, 0, $Maximum);
}

1;
