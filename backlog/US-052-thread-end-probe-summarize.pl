#!/usr/bin/perl
# US-052-thread-end-probe-summarize.pl
# Evidence for backlog story US-052 (Glass Windows toolkit teardown). NOT shipped and NOT built by Maven.
# Reads logs/<pass>/<scenario>.log written by US-052-thread-end-probe-run-all.pl, writes a normalised copy
# (<scenario>.norm: roles instead of thread ids, no timestamps, durations masked) and prints to stdout:
#   - stability: pass pairs r1/r2, m1/m2 (whole normalised log) and b1/b2, mb1/mb2 (callback lines of non-helper
#     threads only, because the helper threads of the behavioural test interleave their own lines)
#   - the derived figures quoted in US-052-thread-end-probe-results.md (S4e/S4f, S5a, S6, S8, S9, S11, S12)
use strict;
use warnings;
use File::Basename;

chdir dirname(__FILE__) or die;
my @pass = grep { -d "logs/$_" } qw(r1 r2 b1 b2 m1 m2 mb1 mb2);
my @plain = grep { !/^m/ } @pass;
my @dmpass = grep { /^m/ } @pass;

sub slurp { my ($f) = @_; open my $fh, '<', $f or return (); my @l = <$fh>; close $fh; @l }
sub scen { my ($p) = @_; sort map { basename($_, '.log') } glob("logs/$p/*.log") }
sub cb_only { grep { !/^tid=HELPER/ && /^tid=\w+ ([FTCDWXL]|DM) / && !/thread_local-ctor/ } @_ }
sub as_set { $_[0] =~ /^S(8|9|10|12)/ }   # lines of racing threads interleave: compare as sorted sets

sub normalise {
    my ($in, $out) = @_;
    my @lines = slurp($in) or return;
    my %role;
    for (@lines) {
        $role{$1} = 'MAIN'     if /tid=(\d+) M role=main/;
        $role{$1} = 'TUT'      if /tid=(\d+) U role=thread-under-test/;
        $role{$1} = 'HOLDER'   if /tid=(\d+) H holder/;
        $role{$1} = 'LAUNCHER' if /tid=(\d+) L /;
        $role{$1} = $2         if /tid=(\d+) (WT|P|H) role=/;
    }
    for (@lines) { $role{$1} = 'HELPER' if /tid=(\d+) .*helperThread=1/ && !$role{$1}; }
    open my $oh, '>', $out or die;
    binmode $oh;
    for (@lines) {
        my $l = $_;
        $l =~ s/^t=\d+ //;
        $l =~ s/\b(tid|wndTid|llOwner|armedByTid|ctorTid|flsValue|flsGetValueNow)=(\d+)/
                "$1=" . ($2 == 0 ? '0' : ($role{$2} || 'OTHER'))/gex;
        $l =~ s/\bhwnd=0x[0-9a-f]+/hwnd=H/;
        $l =~ s/\b(address|probeDllBase)=0x[0-9a-f]+/$1=ADDR/g;
        $l =~ s/\b(elapsedUs|acquireUs|totalUs)=\d+/$1=N/g;
        print $oh $l;
    }
    close $oh;
}

for my $p (@pass) { normalise("logs/$p/$_.log", "logs/$p/$_.norm") for scen($p); }

sub compare {
    my ($p1, $p2, $filter, $what) = @_;
    return unless -d "logs/$p1" && -d "logs/$p2";
    for my $s (scen($p1)) {
        next if $s eq 'S9b';   # 200-iteration stress run: compared by its counters below
        my @a = slurp("logs/$p1/$s.norm");
        my @b = slurp("logs/$p2/$s.norm");
        if ($filter) { @a = cb_only(@a); @b = cb_only(@b); }
        if (as_set($s)) { @a = sort @a; @b = sort @b; }
        my @d = grep { $a[$_] ne ($b[$_] // '') } 0 .. $#a;
        my $same = @a == @b && !@d;
        printf "%-4s %s vs %s (%s, %d lines): %s\n", $s, $p1, $p2, $what, scalar @a,
            $same ? "IDENTICAL" : "DIFFERENT at line(s) " . join(',', map { $_ + 1 } @d) . " (" . @a . "/" . @b . ")";
        for my $i (@d) { print "       $p1: $a[$i]       $p2: " . ($b[$i] // "\n"); }
    }
}
print "== stability\n";
compare('r1', 'r2', 0, 'whole log');
compare('b1', 'b2', 1, 'callback lines of non-helper threads');
compare('m1', 'm2', 0, 'whole log');
compare('mb1', 'mb2', 1, 'callback lines of non-helper threads');

print "\n== launcher verdicts (exit code, watchdog) per scenario, all passes\n";
my %verdict;
for my $p (@pass) {
    for my $s (scen($p)) {
        for (slurp("logs/$p/$s.log")) { $verdict{$s}{$p} = $1 if /L child ended (.*)/; }
    }
}
for my $s (sort keys %verdict) {
    my %v;
    $v{ $verdict{$s}{$_} }++ for keys %{ $verdict{$s} };
    print "$s: ", join('; ', map { "$_ x$v{$_}" } sort keys %v), "\n";
}

print "\n== S6: T/DLL_THREAD_DETACH line of the thread under test -> wait-callback line\n";
for my $p (@pass) {
    my ($tut, $tdet, $fcb, $ret, $w);
    for (slurp("logs/$p/S6.log")) {
        $tut = $1 if /tid=(\d+) U role=thread-under-test/;
        $ret = $1 if defined $tut && /^t=(\d+) tid=$tut U returning/;
        $fcb = $1 if defined $tut && /^t=(\d+) tid=$tut F fls-callback/;
        $tdet = $1 if defined $tut && /^t=(\d+) tid=$tut T tls-callback reason=DLL_THREAD_DETACH/;
        $w = $1 if /^t=(\d+) tid=\d+ W wait-callback fired/;
    }
    next unless defined $w;
    printf "%s: U-returning -> F: %d us; U-returning -> T-detach: %d us; T-detach line -> W line: %d us%s\n", $p,
        $fcb - $ret, $tdet - $ret, $w - $tdet,
        $p =~ /b/ ? "  (behavioural pass: each callback line is written after its own 300 ms helper wait)" : "";
}

print "\n== S4e / S4f: F callback blocks during process exit\n";
for my $s (qw(S4e S4f)) {
    for my $p (@plain) {
        my ($blk, $end, $rest, $after) = (undef, undef, '', 0);
        for (slurp("logs/$p/$s.log")) {
            $blk = $1 if /^t=(\d+) .* F blocking: (Acquire|WaitFor)/;
            if (/^t=(\d+) .* L child ended (.*)/) { $end = $1; $rest = $2; }
            $after++ if defined $blk
                && /(F blocking: (acquired|wait returned)|T tls-callback|thread_local-dtor|static-dtor)/;
        }
        next unless defined $blk;
        printf "%s %s: blocking line -> child ended: %d us; %s; callback lines after the blocking line: %d\n",
            $s, $p, $end - $blk, $rest, $after;
    }
}

print "\n== S5a: thread exit after FreeLibrary without FlsFree\n";
for my $p (@pass) { print "$p: $_" for grep { /UNHANDLED|child ended/ } slurp("logs/$p/S5a.norm"); }

print "\n== S8: calls made while H is parked in T/DLL_THREAD_DETACH (loader lock held)\n";
for my $s (qw(S8a S8b S8c S8d)) {
    for my $p (@plain) {
        my %r;
        for (slurp("logs/$p/$s.norm")) {
            $r{post} = "ret=$1 gle=$2" if /P PostMessageW returned ret=(\d+) gle=(\d+)/;
            $r{send} = "ret=$1 gle=$2" if /P SendNotifyMessageW returned ret=(\d+) gle=(\d+)/;
            $r{post2s} = $1 if /PostMessageW returned within 2 s while H parked yes=(\d)/;
            $r{send2s} = $1 if /SendNotifyMessageW returned within 2 s while H parked yes=(\d)/;
            $r{rx1} = $1 if /WNDPROC received WM_APP\+1 .*hParked=(\d)/;
            $r{rx2} = $1 if /WNDPROC received WM_APP\+2 .*hParked=(\d)/;
            $r{owner} = $1 if /P before PostMessageW.* llOwner=(\w+)/;
            $r{late} = $1 if /after releasing H: both calls completed within 5 s yes=(\d)/;
        }
        for (slurp("logs/$p/$s.log")) {
            $r{postUs} = $1 if /P PostMessageW returned .*elapsedUs=(\d+)/;
            $r{sendUs} = $1 if /P SendNotifyMessageW returned .*elapsedUs=(\d+)/;
        }
        next unless %r;
        printf "%s %s: loader lock owner before the calls=%s | PostMessageW %s %s us within2s=%s"
             . " receivedWhileParked=%s | SendNotifyMessageW %s %s us within2s=%s receivedWhileParked=%s"
             . " | neededReleaseOfH=%s\n",
            $s, $p, map { $_ // 'n/a' } @r{qw(owner post postUs post2s rx1 send sendUs send2s rx2 late)};
    }
}

print "\n== S9a / S9c: DLL_THREAD_DETACH acquires exclusive while a poster holds shared (parked 300 ms)\n";
for my $s (qw(S9a S9c)) {
    for my $p (@pass) {
        my %r;
        for (slurp("logs/$p/$s.log")) {
            $r{who} = $1 if /tid=\d+ (T|DM) DLL_THREAD_DETACH: acquired SRW exclusive/;
            $r{acq} = $1 if /acquired SRW exclusive acquireUs=(\d+)/;
            $r{iw} = $1 if /acquired SRW exclusive .*isWindow=(\d)/;
            $r{post} = "ret=$1 gle=$2 $3 us" if /P PostMessageW returned ret=(\d+) gle=(\d+) elapsedUs=(\d+)/;
            $r{sig300} = $1 if /300 ms later, poster still parked: TUT handle signaled yes=(\d)/;
            $r{after} = "isWindow=$1" if /poster released; thread handle signaled.* isWindow=(\d)/;
            $r{after} = "NOT SIGNALED" if /thread handle NOT signaled/;
            $r{end} = $1 if /L child ended (.*)/;
        }
        next unless %r;
        printf "%s %s: acquire in %s | TUT signaled while poster parked=%s | poster PostMessageW %s"
             . " | acquireUs=%s isWindow inside=%s | after exit %s | %s\n",
            $s, $p, map { $_ // 'n/a' } @r{qw(who sig300 post acq iw after end)};
    }
}

print "\n== S9b: 200 x (create TUT, window, exit) against 2 persistent + 2 fresh posters\n";
for my $p (@plain) {
    my (@acq, $iw0, @sum);
    for (slurp("logs/$p/S9b.log")) {
        if (/acquired SRW exclusive acquireUs=(\d+) .*isWindow=(\d)/) { push @acq, $1; $iw0++ unless $2; }
        push @sum, $1 if /M (S9b (?:summary|fresh).*)/ || /M (S9b: .*?) llOwner=\d+$/;
        push @sum, $1 if /L (child ended .*)/;
    }
    next unless @sum;
    @acq = sort { $a <=> $b } @acq;
    printf "%s: exclusive acquires logged=%d (isWindow=0 inside: %d) acquireUs min=%s median=%s p95=%s max=%s\n",
        $p, scalar @acq, $iw0 // 0, $acq[0] // 'n/a', $acq[@acq / 2] // 'n/a', $acq[int(@acq * 0.95)] // 'n/a',
        $acq[-1] // 'n/a';
    print "      $_\n" for @sum;
}

# One token per callback line of a non-helper thread, in log order, with markers for what the EXE did:
#   <thread>:<mechanism>[/<reason>](w=<IsWindow> ll=<loader lock owned, PEB> [beh=<helper ran>] [resv=<lpReserved>])
print "\n== S11: order of the mechanisms (DM = DllMain of probe_dm.dll)\n";
my %short = (DLL_PROCESS_ATTACH => 'PA', DLL_THREAD_ATTACH => 'TA', DLL_THREAD_DETACH => 'TD',
             DLL_PROCESS_DETACH => 'PD');
my @marks = (
    [qr/LoadLibrary returned/, 'LoadLibrary-returned'], [qr/before DeleteFiber\(F2\)/, 'DeleteFiber(F2)'],
    [qr/after DeleteFiber\(F2\)/, 'DeleteFiber-returned'], [qr/calling ExitProcess/, 'ExitProcess'],
    [qr/RETURNING from wmain|main returning from wmain/, 'main-returns'],
    [qr/calling FreeLibrary/, 'FreeLibrary'], [qr/FreeLibrary returned/, 'FreeLibrary-returned'],
    [qr/calling TerminateThread/, 'TerminateThread'], [qr/U returning from thread procedure/, 'thread-returns'],
    [qr/calling ExitThread/, 'ExitThread'], [qr/calling DeleteFiber on the currently/, 'DeleteFiber(self)'],
    [qr/thread handle signaled/, 'handle-signaled'], [qr/UNHANDLED EXCEPTION/, 'CRASH'],
    [qr/before ConvertFiberToThread/, 'ConvertFiberToThread'],
    [qr/after ConvertFiberToThread ok/, 'ConvertFiberToThread-returned'],
    [qr/W wait-callback fired/, 'wait-callback'],
);
for my $s (scen($dmpass[0] // 'none')) {
    next if $s eq 'S12';
    for my $p (@dmpass) {
        my @tok;
        LINE: for (slurp("logs/$p/$s.norm")) {
            next if /^tid=HELPER/ || /thread_local-ctor/;
            for my $m (@marks) { if ($_ =~ $m->[0]) { push @tok, "[$m->[1]]"; next LINE; } }
            next unless /^tid=(\w+) (F|T|DM|C|D) (\S+)(?: reason=(\w+))?/;
            my ($th, $mech, $rsn) = ($1, $2, $4);
            next if $mech eq 'D' && !/static-dtor helperThread/;
            next if ($mech eq 'T' || $mech eq 'DM') && !$rsn;
            my $t = "$th:$mech" . ($rsn ? "/$short{$rsn}" : '');
            my @f;
            push @f, "w=$1" if /isWindow=(\d)/;
            push @f, "ll=$1" if /llMine=(\d)/;
            push @f, "beh=$1" if /behHelperRan=([012])/;
            push @f, "resv=$1" if $mech eq 'DM' && /reservedNonNull=(\d)/;
            push @tok, "$t(" . join(' ', @f) . ")";
        }
        my $line = "$s $p:";
        for my $t (@tok) {
            if (length($line) - rindex($line, "\n") + length($t) > 116) { $line .= "\n       "; }
            $line .= " $t";
        }
        print "$line\n";
    }
}

print "\n== S12: DisableThreadLibraryCalls on the probe DLL, then one thread starts and exits\n";
for my $p (@pass) {
    my ($r, @cb);
    for (slurp("logs/$p/S12.norm")) {
        $r = "ret=$1 gle=$2" if /DisableThreadLibraryCalls\(probe DLL\) returned ret=(\d) gle=(\d+)/;
        push @cb, "$1/$short{$2}" if defined $r && /^tid=OTHER (T|DM) \S+ reason=(DLL_THREAD_\w+)/;
    }
    next unless defined $r;
    print "$p: $r; notifications on the thread created afterwards: ", (@cb ? join(' ', @cb) : 'none'), "\n";
}
