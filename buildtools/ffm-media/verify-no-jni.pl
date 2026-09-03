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
#   perl buildtools/ffm-media/verify-no-jni.pl [--verbose] [--json]
#
# Exit status is 0 when every check is zero, 1 otherwise.

use strict;
use warnings;
use File::Find;

my $verbose = grep { $_ eq '--verbose' } @ARGV;
my $json    = grep { $_ eq '--json' } @ARGV;

my $module   = 'modules/javafx.media';
my $java_dir = "$module/src/main/java";
my $nat_dir  = "$module/src/main/native";
my $test_dir = "$module/src/test";

die "run from the repository root (no $module)\n" unless -d $module;

# The native trees that are engine code, not JavaFX glue: they never contained
# JNI and are not part of this migration (see FFM-AUDIT-plugins-libs.md).
my $skip_re = qr{
    /gstreamer/gstreamer-lite/ |
    /gstreamer/3rd_party/      |
    /gstreamer/plugins/
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
my @build  = grep { -f $_ } (
    "$module/pom.xml",
    "$module/native/CMakeLists.txt",
    "$module/native/win.cmake",
    "$module/native/linux.cmake",
    "$module/native/mac.cmake",
    collect("$nat_dir/jfxmedia/projects", qr{Makefile}),
    collect("$nat_dir/gstreamer/projects", qr{Makefile}),
);

sub slurp {
    my ($f) = @_;
    open(my $fh, '<', $f) or return '';
    local $/;
    my $t = <$fh>;
    close $fh;
    return defined $t ? $t : '';
}

# Each check: [label, \@files, qr//]. Matches are counted per line.
my @checks = (
    ['Java `native` method declarations', \@java,
        qr{^\s*(?:(?:public|protected|private|static|final|synchronized|abstract)\s+)*native\s+}],
    ['Java references to a JNI-only helper', \@java,
        qr{\b(?:_initIDs|registerNatives|System\.loadLibrary)\b}],
    ['C/C++ including <jni.h>', \@native,
        qr{^\s*#\s*(?:include|import)\s*[<"]jni(?:_md)?\.h[>"]}],
    ['C/C++ naming a JNI type', \@native,
        qr{\b(?:JNIEnv|JavaVM|jobject|jclass|jmethodID|jfieldID|jstring|jobjectArray|jbyteArray|jintArray|jlongArray|jfloatArray|jdoubleArray|jbooleanArray|jweak)\b}],
    ['C/C++ exporting a JNI entry point', \@native,
        qr{\bJNIEXPORT\b}],
    ['C/C++ calling back into Java through JNI', \@native,
        qr{\b(?:Call(?:Static|Nonvirtual)?(?:Void|Object|Boolean|Byte|Char|Short|Int|Long|Float|Double)Method(?:A|V)?|GetMethodID|GetStaticMethodID|GetFieldID|GetStaticFieldID|FindClass|NewGlobalRef|DeleteGlobalRef|NewWeakGlobalRef|NewObjectA?|ThrowNew|ExceptionCheck|ExceptionOccurred|GetStringUTFChars|NewStringUTF|GetStringChars|Set[A-Za-z]+ArrayRegion|Get[A-Za-z]*ArrayElements|GetPrimitiveArrayCritical|NewDirectByteBuffer|GetDirectBufferAddress|AttachCurrentThread(?:AsDaemon)?|DetachCurrentThread|GetJavaVM)\b}],
    ['C/C++ defining JNI_OnLoad', \@native,
        qr{\bJNI_OnLoad\w*\s*\(}],
    ['Generated JNI headers still included', \@native,
        qr{^\s*#\s*(?:include|import)\s*[<"]com_sun_media_[^>"]*\.h[>"]}],
    ['Build files requiring the JDK headers', \@build,
        qr{(?:JAVA_HOME|JDK_HOME)[^\n]*include|(?:^|\s)-h\s}],
    ['Tests referencing JNI', \@tests,
        qr{\b(?:_initIDs|JNIEXPORT|JNIEnv)\b}],
);

my (@rows, $total);
$total = 0;
for my $c (@checks) {
    my ($label, $files, $re) = @$c;
    my ($count, @hits) = (0);
    for my $f (@$files) {
        my $n = 0;
        for my $line (split /\n/, slurp($f)) {
            next if $line =~ m{^\s*(?://|\*|/\*|\#\s*$)};
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

if ($json) {
    print "{\n";
    print qq{  "total": $total,\n  "checks": [\n};
    print join(",\n", map {
        my $f = join(', ', map { qq{"$_"} } @{ $_->{files} });
        qq{    { "check": "$_->{label}", "count": $_->{count}, "files": [$f] }}
    } @rows);
    print "\n  ]\n}\n";
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
}

exit($total == 0 ? 0 : 1);
