#!/usr/bin/perl
# US-052-thread-end-probe-run-all.pl
# Evidence for backlog story US-052 (Glass Windows toolkit teardown). NOT shipped and NOT built by Maven.
# Runs every probe scenario in a fresh process (probe_exe.exe run <scenario> ...), eight passes:
#   r1, r2    probe.dll, loader lock read from PEB->LoaderLock only (no extra threads, timing-clean)
#   b1, b2    probe.dll, plus the behavioural loader-lock test (helper thread inside each callback, 300 ms)
#   m1, m2    probe_dm.dll (own DllMain), PEB only, the scenarios that discriminate the mechanisms
#   mb1, mb2  probe_dm.dll, plus the behavioural test
# Raw logs go to logs/<pass>/<scenario>.log; US-052-thread-end-probe-summarize.pl then writes summary.txt.
# Usage: perl US-052-thread-end-probe-run-all.pl [pass ...]     (PROBE_ONLY=<regex> limits the scenarios)
use strict;
use warnings;
use File::Basename;
use File::Path qw(make_path);

chdir dirname(__FILE__) or die "chdir: $!";
my @all = qw(S1a S1b S2a S2b S2c S3a S3b S4a S4b S4c S4d S4e S4f S5a S5b S6 S7
             S8a S8b S8c S8d S9a S9b S9c S10a S10b S12);
my @dm = qw(S1a S1b S2a S2b S2c S3a S3b S4a S4b S4c S5a S6 S7 S9a S10a S10b S12);
my @pass = @ARGV ? @ARGV : qw(r1 r2 b1 b2 m1 m2 mb1 mb2);
chomp(my $wdir = `cygpath -w .`);

for my $p (@pass) {
    my $is_dm = $p =~ /^m/;
    my $is_beh = $p =~ /b/;
    my @scen = $is_dm ? @dm : @all;
    @scen = grep { /$ENV{PROBE_ONLY}/ } @scen if $ENV{PROBE_ONLY};
    make_path("logs/$p");
    for my $s (@scen) {
        unlink "logs/$p/$s.log";
        $ENV{PROBE_LOG} = "$wdir\\logs\\$p\\$s.log";
        my @cmd = ("./probe_exe.exe", "run", $s);
        push @cmd, "beh" if $is_beh;
        push @cmd, "dm" if $is_dm;
        system(@cmd);
        print "$p $s done\n";
    }
}
system("perl US-052-thread-end-probe-summarize.pl > summary.txt") == 0 or warn "summarize failed\n";
