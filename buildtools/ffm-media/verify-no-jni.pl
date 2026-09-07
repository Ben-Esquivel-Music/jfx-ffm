#!/usr/bin/perl
#
# Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.  Oracle designates this
# particular file as subject to the "Classpath" exception as provided
# by Oracle in the LICENSE file that accompanied this code.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#
# Scoreboard for the javafx.media JNI -> FFM migration: counts everything that
# still ties the module to JNI, so "javafx.media is JNI-free" is a measurement
# rather than a claim. The javafx.web equivalent is
# buildtools/ffm-web/verify-no-jni.pl.
#
#   perl buildtools/ffm-media/verify-no-jni.pl [--verbose] [--json] [--base=<rev>]
#
# The run prints three sections, and only the first one is about JNI:
#
#   1. the JNI residue scoreboard, whose TOTAL is the headline result;
#   2. "Vendored engine trees" - what this branch changed in the third-party
#      trees the JNI checks skip, by file and by delta, as of the WORKING TREE.
#      It exists because "contains no JNI" and "was not modified" are different
#      claims and only the first justifies the skip;
#   3. "Computed counts" - the branch diff totals and the three jfxm_* ABI counts,
#      derived from the repository and compared against the numbers FFM-STATUS.md
#      quotes, so that no size or count in that document is hand arithmetic
#      nothing ever re-ran. Scope here is committed history only, <base>..HEAD,
#      matching what the document says it counts.
#
# Exit status: 0 all clear; 1 JNI residue survives; 2 the JNI side is clean but a
# documented count has moved or the three ABI counts disagree.
#
# --base=<rev> overrides the revision compared against (default:
# git merge-base HEAD master).

use strict;
use warnings;
use File::Find;

my $verbose = grep { $_ eq '--verbose' } @ARGV;
my $json    = grep { $_ eq '--json' } @ARGV;
my ($base)  = map { /^--base=(.*)$/ ? $1 : () } @ARGV;

my $module   = 'modules/javafx.media';
my $java_dir = "$module/src/main/java";
my $nat_dir  = "$module/src/main/native";
my $test_dir = "$module/src/test";

die "run from the repository root (no $module)\n" unless -d $module;

# The native trees that are engine code, not JavaFX glue. Two separate claims
# hold of them, and running them together into one sentence is the mistake this
# comment used to make (FFM-STATUS.md section 1b now states them apart):
#
#   1. They contain zero JNI tokens - see FFM-AUDIT-plugins-libs.md section 1.
#      That, and only that, is what justifies skipping them in a JNI-residue
#      check, and it does not weaken the clean scoreboard below.
#   2. This branch nevertheless modified seven files in them (+500 / -71 as
#      committed). "Contains no JNI" and "was not touched by this migration" are
#      different claims and the second one is false.
#
# The "Vendored engine trees" report at the end of this run is the control for
# claim 2, deliberately kept apart from the JNI counts so that a clean scoreboard
# can never be read as "nothing else changed".
# target/ is build output, not source: the CMake configure step writes the JDK
# include paths it was given into files there, which the build-file check below
# would otherwise count as a JNI dependency of the module.
my $skip_re = qr{
    /gstreamer/gstreamer-lite/ |
    /gstreamer/3rd_party/      |
    /gstreamer/plugins/        |
    /target/
}x;

sub collect {
    my ($dir, $re) = @_;
    my @files;
    return @files unless -d $dir;
    find(sub {
        return unless -f $_;
        my $p = $File::Find::name;
        $p =~ s{\\}{/}g;
        return if $p =~ $skip_re;
        push @files, $p if $p =~ $re;
    }, $dir);
    return sort @files;
}

my @java   = collect($java_dir, qr{\.java$});
my @native = collect($nat_dir,  qr{\.(c|cc|cpp|h|hpp|m|mm)$});
my @tests  = collect($test_dir, qr{\.java$});
# Every build file under the module, not a fixed list. The JNI-era makefiles this
# check was written against - jfxmedia/projects and gstreamer/projects - are gone,
# and naming those two directories would let one reintroduced anywhere else go
# uncounted, which is the one thing this check exists to prevent.
my @build  = collect($module,
    qr{(?:^|/)(?:Makefile[^/]*|[^/]+\.mk|CMakeLists\.txt|[^/]+\.cmake|pom\.xml)$});

sub slurp {
    my ($f) = @_;
    open(my $fh, '<', $f) or return '';
    local $/;
    my $t = <$fh>;
    close $fh;
    return defined $t ? $t : '';
}

# Each check: [label, \@files, qr//, optional qr// of lines to ignore].
# Matches are counted per line.
my @checks = (
    ['Java `native` method declarations', \@java,
        qr{^\s*(?:(?:public|protected|private|static|final|synchronized|abstract)\s+)*native\s+}],
    ['Java references to a JNI-only helper', \@java,
        qr{\b(?:_initIDs|registerNatives|System\.loadLibrary)\b}],
    ['C/C++ including <jni.h>', \@native,
        qr{^\s*#\s*(?:include|import)\s*[<"]jni(?:_md)?\.h[>"]}],
    ['C/C++ naming a JNI type', \@native,
        qr{\b(?:JNIEnv|JavaVM|jobject|jclass|jmethodID|jfieldID|jstring|jobjectArray|jbyteArray|jintArray|jlongArray|jfloatArray|jdoubleArray|jbooleanArray|jweak)\b}],
    # JNICALL is the other half of an entry-point signature and survives on its
    # own in a header or a function-pointer typedef, so it is counted too.
    ['C/C++ exporting a JNI entry point', \@native,
        qr{\b(?:JNIEXPORT|JNICALL)\b}],
    # A mangled Java_<pkg>_<class>_<method> symbol, defined or referenced, with or
    # without JNIEXPORT in front of it.
    ['C/C++ naming a JNI-mangled function', \@native,
        qr{\bJava_[A-Za-z]\w*\s*\(|\bJava_com_sun\w*\b}],
    ['C/C++ calling back into Java through JNI', \@native,
        qr{\b(?:Call(?:Static|Nonvirtual)?(?:Void|Object|Boolean|Byte|Char|Short|Int|Long|Float|Double)Method(?:A|V)?|GetMethodID|GetStaticMethodID|GetFieldID|GetStaticFieldID|FindClass|NewGlobalRef|DeleteGlobalRef|NewWeakGlobalRef|NewObjectA?|ThrowNew|ExceptionCheck|ExceptionOccurred|GetStringUTFChars|NewStringUTF|GetStringChars|Set[A-Za-z]+ArrayRegion|Get[A-Za-z]*ArrayElements|GetPrimitiveArrayCritical|NewDirectByteBuffer|GetDirectBufferAddress|AttachCurrentThread(?:AsDaemon)?|DetachCurrentThread|GetJavaVM|(?:Unregister|Register)Natives)\b}],
    ['C/C++ defining JNI_OnLoad', \@native,
        qr{\bJNI_OnLoad\w*\s*\(}],
    ['Generated JNI headers still included', \@native,
        qr{^\s*#\s*(?:include|import)\s*[<"]com_sun_media_[^>"]*\.h[>"]}],
    # Named variables are not enough: a build could reach the JDK headers through
    # any variable name, through CMake's own FindJNI, or by naming the header or
    # the platform include subdirectory outright. All of those are counted, and
    # whole-line build-file comments (which cannot affect a build) are not.
    ['Build files requiring the JDK headers', \@build,
        qr{
            (?:JAVA_HOME|JDK_HOME)[^\n]*include
          | \b[A-Z][A-Z0-9_]*(?:JAVA|JDK|JVM)[A-Z0-9_]*\b[^\n]*(?i:include)
          | \bjni(?:_md)?\.h\b
          | (?i:include)/(?:win32|linux|darwin|solaris)\b
          | \bfind_package\s*\(\s*JNI\b
          | \b(?:JNI_INCLUDE_DIRS|JAVA_INCLUDE_PATH2?|JAVA_AWT_INCLUDE_PATH)\b
          | \bjavah\b
          | (?:^|\s)-h\s
        }x,
        qr{^\s*\#}],
    ['Tests referencing JNI', \@tests,
        qr{\b(?:_initIDs|JNIEXPORT|JNICALL|JNIEnv|RegisterNatives)\b}],
);

my (@rows, $total);
$total = 0;
for my $c (@checks) {
    my ($label, $files, $re, $skip_line) = @$c;
    my ($count, @hits) = (0);
    for my $f (@$files) {
        my $n = 0;
        for my $line (split /\n/, slurp($f)) {
            next if $line =~ m{^\s*(?://|\*|/\*|\#\s*$)};
            next if $skip_line && $line =~ $skip_line;
            $n++ if $line =~ $re;
        }
        if ($n) {
            $count += $n;
            push @hits, "$f ($n)";
        }
    }
    $total += $count;
    push @rows, { label => $label, count => $count, files => \@hits };
}

# ---------------------------------------------------------------------------
# A separate control, and NOT a JNI check: what this branch changed in the
# vendored engine trees the checks above skip. Those trees hold no JNI, which is
# the whole justification for skipping them - but they are also where this branch
# made its largest behaviour changes, and no other control in the project covers
# them. Reported here, by file and by delta, so the scoreboard cannot be silent
# about them. It never contributes to TOTAL and never changes the exit status.
# ---------------------------------------------------------------------------
my @vendored_dirs = (
    "$nat_dir/gstreamer/gstreamer-lite",
    "$nat_dir/gstreamer/3rd_party",
    "$nat_dir/gstreamer/plugins",
);

sub git_out {
    my ($args) = @_;
    my $out = `git $args 2>&1`;
    return ($? == 0) ? $out : undef;
}

sub vendored_changes {
    my $rev = $base;
    unless (defined $rev && length $rev) {
        for my $ref ('master', 'origin/master') {
            my $mb = git_out("merge-base HEAD $ref");
            next unless defined $mb;
            ($rev) = $mb =~ /([0-9a-f]{7,40})/;
            last if defined $rev;
        }
    }
    return { error => 'cannot resolve a merge base; pass --base=<rev>' }
        unless defined $rev && length $rev;

    my $paths = join(' ', @vendored_dirs);
    my $numstat = git_out("diff --numstat $rev -- $paths");
    return { error => "git diff against $rev failed" } unless defined $numstat;

    my @files;
    my ($added, $deleted) = (0, 0);
    for my $line (split /\n/, $numstat) {
        my ($a, $d, $path) = split /\t/, $line, 3;
        next unless defined $path;
        my $binary = ($a eq '-') ? 1 : 0;
        my $na = $binary ? 0 : $a + 0;
        my $nd = $binary ? 0 : $d + 0;
        $added += $na;
        $deleted += $nd;
        push @files, { file => $path, added => $na, deleted => $nd, binary => $binary };
    }

    my @untracked;
    my $others = git_out("ls-files --others --exclude-standard -- $paths");
    @untracked = grep { length } split /\n/, $others if defined $others;

    return { rev => $rev, files => \@files, added => $added,
             deleted => $deleted, untracked => \@untracked };
}

my $vendored = vendored_changes();

# ---------------------------------------------------------------------------
# Computed counts. Every size FFM-STATUS.md quotes about this branch and about
# the jfxm_* ABI is derived here, from the repository, so that none of them stays
# hand arithmetic that nothing re-runs - which is what five of the review's
# documentation findings turned out to be. The scope is the one FFM-STATUS.md
# states it counts: committed history only, <base>..HEAD, so an uncommitted
# working tree never moves a published figure. (The vendored table above is the
# opposite on purpose: it reports the working tree, because its job is to show
# what is changing right now.)
# ---------------------------------------------------------------------------
sub diff_totals {
    my ($range, $paths) = @_;
    my $spec = @$paths ? ' -- ' . join(' ', @$paths) : '';
    my $out = git_out("diff --numstat $range$spec");
    return undef unless defined $out;
    my %r = (files => 0, added => 0, deleted => 0);
    for my $line (split /\n/, $out) {
        my ($a, $d, $path) = split /\t/, $line, 3;
        next unless defined $path;
        $r{files}++;
        next if $a eq '-';                       # binary
        $r{added}   += $a;
        $r{deleted} += $d;
    }
    return \%r;
}

sub count_unique {
    my ($file, $re) = @_;
    my %seen;
    for my $line (split /\n/, slurp($file)) {
        next if $line =~ m{^\s*(?://|\*|/\*)};
        while ($line =~ /$re/g) { $seen{$1} = 1; }
    }
    return scalar keys %seen;
}

my $abi_header = "$nat_dir/jfxmedia/jfxmedia_api.h";
my $abi_impl   = "$nat_dir/jfxmedia/ffi/jfxmedia_api.cpp";
my $abi_java   = "$java_dir/com/sun/media/jfxmediaimpl/JfxMediaNative.java";

my %stats;
$stats{abi} = {
    # JFXM_EXPORT <type> jfxm_x(...)  - the exported declarations, one per entry point
    declared => count_unique($abi_header, qr{^\s*JFXM_EXPORT\b[^(]*\b(jfxm_[a-z0-9_]+)\s*\(}),
    # a definition starts in column 0; a call inside a function body is indented
    defined  => count_unique($abi_impl,   qr{^[A-Za-z_][^(]*\b(jfxm_[a-z0-9_]+)\s*\(}),
    # every downcall handle is looked up by its literal symbol name
    bound    => count_unique($abi_java,   qr{"(jfxm_[a-z0-9_]+)"}),
};
($stats{abi_version}) = (slurp($abi_header) =~ /define\s+JFXM_ABI_VERSION\s+(\d+)/);
$stats{abi_version} = 0 unless defined $stats{abi_version};

$stats{scopes} = [];
unless ($vendored->{error}) {
    $stats{range} = "$vendored->{rev}..HEAD";
    push @{ $stats{scopes} },
        ['Whole branch',           diff_totals($stats{range}, [])],
        ['src/main/native',        diff_totals($stats{range}, [$nat_dir])],
        ['Vendored engine trees',  diff_totals($stats{range}, \@vendored_dirs)];
}

# Does FFM-STATUS.md quote what was just computed? Numbers are compared after
# thousands separators are stripped, so the doc is free to format them any way it
# likes; a value the doc does not contain anywhere is a stale count. This is
# corroboration, not proof - but it is the control that was missing.
my $status_md = "$module/FFM-STATUS.md";
my %doc_numbers;
my $doc_text = slurp($status_md);
if (length $doc_text) {
    while ($doc_text =~ /(\d[\d,]*)/g) {
        my $v = $1;
        $v =~ s/,//g;
        $doc_numbers{$v} = 1;
    }
}
my @expected;
for my $s (@{ $stats{scopes} }) {
    my ($label, $r) = @$s;
    next unless $r;
    push @expected, ["$label files",   $r->{files}],
                    ["$label added",   $r->{added}],
                    ["$label deleted", $r->{deleted}];
}
push @expected, ['jfxm_* declared', $stats{abi}{declared}],
                ['jfxm_* defined',  $stats{abi}{defined}],
                ['jfxm_* bound',    $stats{abi}{bound}],
                ['JFXM_ABI_VERSION', $stats{abi_version}];
my @stale;
if (length $doc_text) {
    for my $e (@expected) {
        push @stale, $e->[0] unless $doc_numbers{ $e->[1] };
    }
}
# Independent of any document: the three ABI counts must agree with each other.
my $abi_consistent = ($stats{abi}{declared} == $stats{abi}{defined}
                   && $stats{abi}{defined}  == $stats{abi}{bound}
                   && $stats{abi}{declared} > 0) ? 1 : 0;

sub vendored_json {
    my ($v) = @_;
    return qq({ "error": "$v->{error}" }) if $v->{error};
    my $files = join(', ', map {
        qq({ "file": "$_->{file}", "added": $_->{added}, "deleted": $_->{deleted}, "binary": )
            . ($_->{binary} ? 'true' : 'false') . ' }'
    } @{ $v->{files} });
    my $untracked = join(', ', map { qq("$_") } @{ $v->{untracked} });
    return qq({ "base": "$v->{rev}", "added": $v->{added}, "deleted": $v->{deleted}, )
         . qq("files": [$files], "untracked": [$untracked] });
}

if ($json) {
    print "{\n";
    print qq{  "total": $total,\n  "checks": [\n};
    print join(",\n", map {
        my $f = join(', ', map { qq{"$_"} } @{ $_->{files} });
        qq{    { "check": "$_->{label}", "count": $_->{count}, "files": [$f] }}
    } @rows);
    print "\n  ],\n";
    print qq{  "vendored": }, vendored_json($vendored), ",\n";
    my $scopes = join(', ', map {
        my ($label, $r) = @$_;
        $r ? qq({ "scope": "$label", "files": $r->{files}, "added": $r->{added}, "deleted": $r->{deleted} })
           : ()
    } @{ $stats{scopes} });
    my $stale_json = join(', ', map { qq("$_") } @stale);
    my $range = defined $stats{range} ? $stats{range} : '';
    my $consistent = $abi_consistent ? 'true' : 'false';
    my $counts = '{ "range": "' . $range . '", "scopes": [' . $scopes . '], '
               . '"abi_declared": ' . $stats{abi}{declared} . ', '
               . '"abi_defined": ' . $stats{abi}{defined} . ', '
               . '"abi_bound": ' . $stats{abi}{bound} . ', '
               . '"abi_version": ' . $stats{abi_version} . ', '
               . '"abi_consistent": ' . $consistent . ', '
               . '"stale_in_status_md": [' . $stale_json . '] }';
    print '  "counts": ', $counts, "\n";
    print "}\n";
} else {
    printf "%-46s %6s\n", 'Check', 'Count';
    printf "%-46s %6s\n", '-' x 46, '-' x 6;
    for my $r (@rows) {
        printf "%-46s %6d\n", $r->{label}, $r->{count};
        if ($verbose && @{ $r->{files} }) {
            print "    $_\n" for @{ $r->{files} };
        }
    }
    printf "%-46s %6s\n", '-' x 46, '-' x 6;
    printf "%-46s %6d\n", 'TOTAL', $total;
    print "\njavafx.media is free of JNI.\n" if $total == 0;

    print "\nVendored engine trees changed by this branch (NOT a JNI check)\n";
    printf "%-46s %6s\n", '-' x 46, '-' x 6;
    if ($vendored->{error}) {
        print "  $vendored->{error}\n";
    } else {
        my $short = substr($vendored->{rev}, 0, 10);
        my $n_changed = scalar @{ $vendored->{files} };
        my $n_new     = scalar @{ $vendored->{untracked} };
        if ($n_changed == 0 && $n_new == 0) {
            print "  unchanged against $short\n";
        } else {
            print "  against $short:\n";
            for my $r (@{ $vendored->{files} }) {
                printf "    %7s %7s  %s\n",
                    ($r->{binary} ? 'bin' : '+' . $r->{added}),
                    ($r->{binary} ? 'bin' : '-' . $r->{deleted}),
                    $r->{file};
            }
            print "    new (untracked)   $_\n" for @{ $vendored->{untracked} };
            printf "    %d file(s), +%d -%d  (working tree, uncommitted edits included)\n",
                $n_changed, $vendored->{added}, $vendored->{deleted};
            my $committed = $stats{scopes}[2] ? $stats{scopes}[2][1] : undef;
            printf "    %d file(s), +%d -%d  (committed only, %s - the figure FFM-STATUS.md quotes)\n",
                $committed->{files}, $committed->{added}, $committed->{deleted}, $stats{range}
                if $committed;
        }
        print "  Skipped above because they contain no JNI tokens. That is a claim about\n";
        print "  JNI residue and nothing else; it does not say the branch left them alone.\n";
    }

    print "\nComputed counts (FFM-STATUS.md is expected to quote these)\n";
    printf "%-46s %6s\n", '-' x 46, '-' x 6;
    if (@{ $stats{scopes} }) {
        print "  git diff --numstat $stats{range}\n";
        for my $s (@{ $stats{scopes} }) {
            my ($label, $r) = @$s;
            next unless $r;
            printf "    %-24s %4d files  %+7d %+7d\n",
                $label, $r->{files}, $r->{added}, -$r->{deleted};
        }
    } else {
        print "  no merge base, so no diff counts; pass --base=<rev>\n";
    }
    printf "    %-24s %d declared / %d defined / %d bound%s\n", 'jfxm_* ABI',
        $stats{abi}{declared}, $stats{abi}{defined}, $stats{abi}{bound},
        ($abi_consistent ? '' : '   <== THE THREE DISAGREE');
    printf "    %-24s %d\n", 'JFXM_ABI_VERSION', $stats{abi_version};
    if (!length $doc_text) {
        print "  $status_md not found, so nothing was compared against it\n";
    } elsif (@stale) {
        print "  STALE in FFM-STATUS.md - it does not quote: ", join(', ', @stale), "\n";
        print "  Regenerate those numbers in the document from the values above.\n";
    } else {
        print "  FFM-STATUS.md quotes every value above.\n";
    }
}

# 1: JNI residue survives. 2: the JNI side is clean but a count the documentation
# quotes has moved, or the three ABI counts disagree. Distinct codes so a caller
# that only wants the JNI answer can still have it.
exit(1) if $total != 0;
exit(2) if @stale || !$abi_consistent;
exit(0);
