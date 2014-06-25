#!/usr/bin/perl
# Obfuscates the given api key and secret and puts them in res/raw/
#
# This really only provides enough "security" to defeat a cursory search;
# anything more than that would be pointless, anyway.

if(@ARGV != 2) {
	print "Usage: ./obfuscate_twitter_keys.pl [API Key] [API Secret]\n";
	exit;
}

sub obfuscate_string {
	my $result;

	while($_[0] =~ /(.)/g) {
		my $r = chr int rand 255;

		$result .= ($r ^ $1) . $r;
	}

	$result;
}

open KEY, ">res/raw/twitter_consumer_key" or die $!;
print KEY obfuscate_string $ARGV[0];
close KEY;

open SECRET, ">res/raw/twitter_consumer_secret" or die $!;
print SECRET obfuscate_string $ARGV[1];
close SECRET;

