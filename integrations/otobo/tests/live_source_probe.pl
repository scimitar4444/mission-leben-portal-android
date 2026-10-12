#!/usr/bin/perl
# Signature/TLS contract probe only: deliberately unmapped .invalid recipient.
# Must return ignored=true and must not create or deliver a notification.
use v5.24;
use strict;
use warnings;
use Digest::SHA qw(sha256_hex hmac_sha256);
use MIME::Base64 qw(encode_base64);
use JSON::PP ();
require '/opt/otobo/Custom/Kernel/System/Ticket/Event/MissionLebenPush.pm';
open my $Handle, '<', '/opt/otobo/var/mission-leben-push.hmac' or die 'source key unavailable';
my $Secret = do {local $/; <$Handle>};
close $Handle;
$Secret =~ s/\s+\z//;
my $Body = JSON::PP->new->canonical->utf8->encode({
    recipient_login => 'ml-push-contract-probe@example.invalid',
    ticket_id => '1', article_id => '1', ticket_number => 'contract-probe',
    title => 'No real ticket', preview => '',
});
my $Timestamp = '' . time;
my $Signature = encode_base64(hmac_sha256(join("\n", 'ML-SOURCE-V1', 'otobo', $Timestamp, sha256_hex($Body)), $Secret), '');
$Signature =~ tr{+/}{-_};
$Signature =~ s/=+\z//;
my $Response = Kernel::System::Ticket::Event::MissionLebenPush->new()->_Post(
    'https://id.mission-leben.de/device-bridge/sources/otobo', $Body,
    {'Content-Type' => 'application/json', 'X-ML-Source' => 'otobo',
        'X-ML-Timestamp' => $Timestamp, 'X-ML-Signature' => $Signature},
);
die "contract probe HTTP $Response->{status}\n" if $Response->{status} != 202;
my $Result = JSON::PP->new->decode($Response->{content});
die 'unmapped recipient not ignored' if !$Result->{ignored};
print "verified TLS + dedicated HMAC: 202, unmapped recipient ignored, no push\n";
