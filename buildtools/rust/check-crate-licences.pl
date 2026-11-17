#!/usr/bin/env perl
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

# Licence gate of the Rust crates linked into the shipped native libraries.
#
#   perl buildtools/rust/check-crate-licences.pl [--cargo <cargo>] [--config <cargo config>] [--metadata <file>]
#       [--linked <package>]...
#   perl buildtools/rust/check-crate-licences.pl --self-test
#
# Reads "cargo metadata --locked --offline --format-version 1", run in the repository root (two directories
# above this file) with <cargo>, else the first cargo on PATH, else the one in $CARGO_HOME/bin when CARGO_HOME
# is set and not empty, else the one in ~/.cargo/bin (the lookup of buildtools/rust/jfx_rust.cmake), and with
# --config <cargo config> when given, as jfx_rust_lint passes the build's; or reads that JSON from <file>.
# The shipped crates are the workspace members with a staticlib target. The build links a crate whatever its
# Cargo.toml lists, so jfx_rust_lint names each crate it links with --linked, and one that is not a workspace
# member with a staticlib target is a violation. For each shipped crate, the crates it links are those reached
# through normal dependencies (resolve graph, dep_kinds), proc macros excluded:
#   - a workspace member must be licensed exactly GPL-2.0-only WITH Classpath-exception-2.0;
#   - a third-party crate's SPDX expression must allow MIT, BSD-2-Clause, BSD-3-Clause, ISC, Zlib or
#     Unicode-3.0 (of an OR, the option taken is printed); MPL-2.0 and LGPL-2.1 pass only with a line
#     "Maintainer sign-off: <name> <version> ..." in the module's src/main/legal/rust-crates.md; anything
#     else fails: Apache-2.0 alone, GPL-3.0, LGPL-3.0, AGPL, an unknown or a missing licence;
#   - a linked third-party crate needs a heading "## <name> <version>" in the module's rust-crates.md;
#   - a module with a shipped crate must have src/main/legal/rust-std.md, whose first "## " heading ends
#     with the channel that rust-toolchain.toml in the repository root pins.
# Proc macros and build dependencies are listed, not gated; dev dependencies are not followed.
# Exit 0 with a table, 1 with one line per violation, 2 when the metadata or rust-toolchain.toml cannot be
# read: metadata without a resolve graph, or with an edge that has no dep_kinds, would hide dependencies.
# Core modules only (JSON::PP), so that it runs with the perl of Git for Windows.

use strict;
use warnings;

use Config;
use Cwd qw(abs_path);
use File::Basename qw(dirname);
use File::Spec;
use File::Temp;
use JSON::PP;

my $OWN_LICENCE = 'GPL-2.0-only WITH Classpath-exception-2.0';
my %ALLOWED = map { $_ => 1 } qw(MIT BSD-2-Clause BSD-3-Clause ISC Zlib Unicode-3.0);
my %SIGN_OFF = map { $_ => 1 } qw(MPL-2.0 LGPL-2.1 LGPL-2.1-only LGPL-2.1-or-later);

my $USAGE = "usage: perl check-crate-licences.pl [--cargo <cargo>] [--config <cargo config>] [--metadata <file>]"
    . " [--linked <package>]... | --self-test\n";

sub parse_args {
    my @args = @_;
    my %opt = (linked => []);
    while (@args) {
        my $arg = shift @args;
        if ($arg eq '--self-test') {
            $opt{self_test} = 1;
        } elsif ($arg eq '--cargo' && @args) {
            $opt{cargo} = shift @args;
        } elsif ($arg eq '--config' && @args) {
            $opt{config} = shift @args;
        } elsif ($arg eq '--metadata' && @args) {
            $opt{metadata} = shift @args;
        } elsif ($arg eq '--linked' && @args) {
            push @{ $opt{linked} }, shift @args;
        } else {
            die $USAGE;
        }
    }
    return \%opt;
}

# ---------------------------------------------------------------------------------------------------------
# SPDX expressions: parsed into the alternatives an OR offers, each the list of licences it needs together
# ---------------------------------------------------------------------------------------------------------
sub spdx_tokens {
    my ($expr) = @_;
    my @tokens;
    for my $word (split /\s+/, $expr) {
        push @tokens, grep { length } split /([()\/])/, $word;
    }
    return @tokens;
}

sub spdx_alternatives {
    my ($expr) = @_;
    my @tokens = spdx_tokens($expr);
    my $pos = 0;
    my ($or, $and, $atom);
    $or = sub {
        my @alts = @{ $and->() };
        while ($pos < @tokens && ($tokens[$pos] =~ /^OR$/i || $tokens[$pos] eq '/')) {
            $pos++;
            push @alts, @{ $and->() };
        }
        return \@alts;
    };
    $and = sub {
        my @alts = @{ $atom->() };
        while ($pos < @tokens && $tokens[$pos] =~ /^AND$/i) {
            $pos++;
            my @right = @{ $atom->() };
            @alts = map { my $l = $_; map { [@$l, @$_] } @right } @alts;
        }
        return \@alts;
    };
    $atom = sub {
        die "unexpected end\n" if $pos >= @tokens;
        my $token = $tokens[$pos++];
        if ($token eq '(') {
            my $alts = $or->();
            die "missing )\n" unless $pos < @tokens && $tokens[$pos++] eq ')';
            return $alts;
        }
        die "unexpected \"$token\"\n" if $token =~ /^(\)|\/|AND|OR|WITH)$/i;
        $token =~ s/\+$/-or-later/;
        if ($pos < @tokens && $tokens[$pos] =~ /^WITH$/i) {
            $pos += 2;
            die "WITH needs an exception\n" if $pos > @tokens;
        }
        return [[$token]];
    };
    my $alts = eval {
        my $result = $or->();
        die "trailing \"$tokens[$pos]\"\n" if $pos < @tokens;
        $result;
    };
    return $alts;
}

# ('allowed', option) | ('sign-off', option, [licences needing it]) | ('rejected', reason)
sub judge_licence {
    my ($expr) = @_;
    return ('rejected', 'no licence') unless defined $expr && $expr =~ /\S/;
    my $alts = spdx_alternatives($expr);
    return ('rejected', "unreadable SPDX expression \"$expr\"") unless $alts && @$alts;
    for my $alt (@$alts) {
        return ('allowed', join(' AND ', @$alt)) unless grep { !$ALLOWED{$_} } @$alt;
    }
    for my $alt (@$alts) {
        unless (grep { !$ALLOWED{$_} && !$SIGN_OFF{$_} } @$alt) {
            return ('sign-off', join(' AND ', @$alt), [grep { $SIGN_OFF{$_} } @$alt]);
        }
    }
    return ('rejected', "\"$expr\" offers none of " . join(', ', sort keys %ALLOWED));
}

# ---------------------------------------------------------------------------------------------------------
# The check: metadata (decoded JSON) and a reader of legal files in, report lines and violations out
# ---------------------------------------------------------------------------------------------------------
sub module_of {
    my ($manifest) = @_;
    my @parts = split m{[/\\]}, $manifest;
    return $parts[-6] if @parts >= 7 && $parts[-1] eq 'Cargo.toml' && $parts[-7] eq 'modules'
        && join('/', @parts[-5 .. -3]) eq 'src/main/native-rust';
    return undef;
}

# Dies with the reason when the metadata lacks what the walk below relies on
sub require_resolve_graph {
    my ($meta) = @_;
    die "it has no resolve graph (\"resolve\" with a \"nodes\" array)\n"
        unless ref $meta->{resolve} eq 'HASH' && ref $meta->{resolve}{nodes} eq 'ARRAY';
    for my $node (@{ $meta->{resolve}{nodes} }) {
        for my $dep (@{ $node->{deps} || [] }) {
            my $to = defined $dep->{pkg} ? $dep->{pkg} : '(no pkg)';
            die "the edge from $node->{id} to $to has no dep_kinds\n"
                unless ref $dep->{dep_kinds} eq 'ARRAY' && @{ $dep->{dep_kinds} };
        }
    }
}

sub pinned_channel {
    my ($toml) = @_;
    return defined $toml && $toml =~ /^[ \t]*channel[ \t]*=[ \t]*"([^"]+)"/m ? $1 : undef;
}

sub check {
    my ($meta, $read_legal, $channel, $linked) = @_;
    require_resolve_graph($meta);
    my (%pkg, %deps, %member);
    $pkg{ $_->{id} } = $_ for @{ $meta->{packages} };
    $member{$_} = 1 for @{ $meta->{workspace_members} };
    $deps{ $_->{id} } = $_->{deps} for @{ $meta->{resolve}{nodes} };
    my $is_proc_macro = sub {
        my ($p) = @_;
        return scalar grep { grep { $_ eq 'proc-macro' } @{ $_->{kind} } } @{ $p->{targets} };
    };

    my (@rows, @violations, @build_rows);
    my @shipped = grep { my $p = $pkg{$_};
                         $p && grep { grep { $_ eq 'staticlib' } @{ $_->{crate_types} } } @{ $p->{targets} } }
                  sort @{ $meta->{workspace_members} };
    my %is_shipped = map { $_ => 1 } @shipped;
    my %member_named = map { $pkg{$_} ? ($pkg{$_}{name} => $_) : () } @{ $meta->{workspace_members} };
    for my $name (@{ $linked || [] }) {
        my $id = $member_named{$name};
        if (!defined $id) {
            push @violations, "$name is linked by the build, but is no workspace member: it must be one, and its "
                . "Cargo.toml must list the staticlib crate type, so that this gate covers it";
        } elsif (!$is_shipped{$id}) {
            push @violations, "$name $pkg{$id}{version} is linked by the build, but its Cargo.toml lists no "
                . "staticlib crate type: it must, so that this gate covers it";
        }
    }
    for my $root (@shipped) {
        my $rp = $pkg{$root};
        my $module = module_of($rp->{manifest_path});
        unless (defined $module) {
            push @violations, "$rp->{name} $rp->{version} has a staticlib target but is not in "
                . "modules/<module>/src/main/native-rust/, so no module's legal notices cover it";
            next;
        }
        my $legal = "modules/$module/src/main/legal";
        my $std_md = $read_legal->($module, 'rust-std.md');
        if (!defined $std_md) {
            push @violations, "$legal/rust-std.md is missing: $module ships the Rust standard library in $rp->{name}";
        } else {
            my ($heading) = $std_md =~ /^(##[ \t][^\r\n]*)/m;
            unless (defined $heading && $heading =~ /[ \t]\Q$channel\E[ \t]*$/) {
                push @violations, "$legal/rust-std.md: its heading "
                    . (defined $heading ? "\"$heading\"" : '(none)') . " does not name the Rust toolchain "
                    . "$channel that rust-toolchain.toml pins, so the notice may be for another standard library";
            }
        }
        my $crates_md = $read_legal->($module, 'rust-crates.md');

        # Linked: reached through normal edges. Build-time: proc macros, build dependencies and theirs.
        my (%linked, %build, @queue, @build_queue);
        @queue = ($root);
        $linked{$root} = 1;
        while (defined(my $id = shift @queue)) {
            for my $dep (@{ $deps{$id} || [] }) {
                my @kinds = map { defined $_->{kind} ? $_->{kind} : 'normal' } @{ $dep->{dep_kinds} || [] };
                my $to = $dep->{pkg};
                if (grep { $_ eq 'normal' } @kinds) {
                    if ($is_proc_macro->($pkg{$to})) {
                        push @build_queue, $to unless $build{$to}++;
                    } elsif (!$linked{$to}++) {
                        push @queue, $to;
                    }
                }
                push @build_queue, $to if (grep { $_ eq 'build' } @kinds) && !$build{$to}++;
            }
        }
        while (defined(my $id = shift @build_queue)) {
            for my $dep (@{ $deps{$id} || [] }) {
                my @kinds = map { defined $_->{kind} ? $_->{kind} : 'normal' } @{ $dep->{dep_kinds} || [] };
                next unless grep { $_ ne 'dev' } @kinds;
                push @build_queue, $dep->{pkg} unless $build{ $dep->{pkg} }++;
            }
        }

        for my $id (sort { $pkg{$a}{name} cmp $pkg{$b}{name} } keys %linked) {
            my $p = $pkg{$id};
            my $licence = defined $p->{license} ? $p->{license} : '';
            my $what = "$p->{name} $p->{version}";
            if ($member{$id}) {
                if ($licence eq $OWN_LICENCE) {
                    push @rows, [$p->{name}, $p->{version}, $licence, $module, 'own crate'];
                } else {
                    push @violations, "$what is a crate of this repository linked into $module: its licence "
                        . "must be \"$OWN_LICENCE\", not \"$licence\"";
                }
                next;
            }
            my ($verdict, $detail, $needs) = judge_licence($p->{license});
            my $listed = defined $crates_md && $crates_md =~ /^##[ \t]+\Q$p->{name}\E[ \t]+v?\Q$p->{version}\E[ \t]*$/m;
            push @violations, "$what is linked into $module but has no \"## $what\" heading in "
                . "$legal/rust-crates.md" unless $listed;
            if ($verdict eq 'rejected') {
                push @violations, "$what is linked into $module and its licence is rejected: $detail";
            } elsif ($verdict eq 'sign-off') {
                my $signed = defined $crates_md
                    && $crates_md =~ /^Maintainer sign-off:[ \t]+\Q$p->{name}\E[ \t]+v?\Q$p->{version}\E(?=[ \t]|$)/m;
                if ($signed) {
                    push @rows, [$p->{name}, $p->{version}, $licence, $module, "taken under $detail (signed off)"];
                } else {
                    push @violations, "$what is linked into $module under $detail, which needs a line "
                        . "\"Maintainer sign-off: $what ...\" in $legal/rust-crates.md for "
                        . join(', ', @$needs);
                }
            } else {
                my $taken = $licence eq $detail ? $detail : "taken under $detail";
                push @rows, [$p->{name}, $p->{version}, $licence, $module, $taken];
            }
        }
        for my $id (sort { $pkg{$a}{name} cmp $pkg{$b}{name} } grep { !$linked{$_} } keys %build) {
            my $p = $pkg{$id};
            push @build_rows, [$p->{name}, $p->{version}, defined $p->{license} ? $p->{license} : '(none)',
                $module, 'build time only, not gated'];
        }
    }
    return (\@rows, \@build_rows, \@violations, scalar @shipped);
}

sub print_table {
    my ($rows) = @_;
    my @head = ('crate', 'version', 'licence', 'module', 'result');
    my @width = map { length } @head;
    for my $r (@$rows) {
        $width[$_] = length $r->[$_] > $width[$_] ? length $r->[$_] : $width[$_] for 0 .. $#head;
    }
    my $fmt = join('  ', map { "%-${_}s" } @width[0 .. $#width - 1]) . "  %s\n";
    printf $fmt, @head;
    printf $fmt, @$_ for @$rows;
}

# ---------------------------------------------------------------------------------------------------------
# The real check
# ---------------------------------------------------------------------------------------------------------
# Where rustup installs, searched after PATH: $CARGO_HOME/bin, else ~/.cargo/bin; undef without a home.
# Never both: a rustup proxy starts its tool with $CARGO_HOME/bin first on PATH, so one found outside it fails.
sub cargo_hint_dir {
    return "$ENV{CARGO_HOME}/bin" if defined $ENV{CARGO_HOME} && length $ENV{CARGO_HOME};
    my $home = ($^O eq 'MSWin32' || $^O eq 'msys' || $^O eq 'cygwin') && defined $ENV{USERPROFILE}
        ? $ENV{USERPROFILE} : $ENV{HOME};
    return defined $home ? "$home/.cargo/bin" : undef;
}

sub find_cargo {
    my $exe = ($^O eq 'MSWin32' || $^O eq 'msys' || $^O eq 'cygwin') ? 'cargo.exe' : 'cargo';
    my @dirs = split /\Q$Config{path_sep}\E/, defined $ENV{PATH} ? $ENV{PATH} : '';
    my $hint = cargo_hint_dir();
    push @dirs, $hint if defined $hint;
    for my $dir (@dirs) {
        next unless length $dir;
        for my $name ($exe, 'cargo') {
            my $path = File::Spec->catfile($dir, $name);
            return $path if -f $path && -x _;
        }
    }
    return undef;
}

sub metadata_command {
    my ($cargo, $config) = @_;
    return ($cargo, 'metadata', '--locked', '--offline', (defined $config ? ('--config', $config) : ()),
        '--format-version', '1');
}

sub real_check {
    my ($cargo, $metadata_file, $config, $linked) = @_;
    my $root = abs_path(File::Spec->catdir(dirname(abs_path(__FILE__)), '..', '..'));
    my $json;
    if (defined $metadata_file) {
        open(my $in, '<:raw', $metadata_file) or die "check-crate-licences: cannot read $metadata_file: $!\n";
        local $/;
        $json = <$in>;
        close $in;
    } else {
        $cargo = find_cargo() unless defined $cargo;
        unless (defined $cargo) {
            my $hint = cargo_hint_dir();
            print STDERR "check-crate-licences: no cargo on PATH"
                . (defined $hint ? " or in $hint" : '')
                . " (after PATH, \$CARGO_HOME/bin when CARGO_HOME is set, else ~/.cargo/bin)\n";
            exit 2;
        }
        chdir $root or die "check-crate-licences: cannot enter $root: $!\n";
        # A missing pinned toolchain must fail here, not be downloaded by the rustup proxy
        local $ENV{RUSTUP_AUTO_INSTALL} = '0';
        my @command = metadata_command($cargo, $config);
        open(my $out, '-|', @command) or die "check-crate-licences: cannot run $cargo: $!\n";
        binmode $out;
        local $/;
        $json = <$out>;
        unless (close $out) {
            print STDERR "check-crate-licences: \"@command\" failed in $root (exit status $?)\n";
            exit 2;
        }
    }
    my $meta = eval { JSON::PP->new->decode($json) };
    unless ($meta) {
        print STDERR "check-crate-licences: the cargo metadata is not JSON: $@";
        exit 2;
    }
    my $read_legal = sub {
        my ($module, $file) = @_;
        my $path = File::Spec->catfile($root, 'modules', $module, 'src', 'main', 'legal', $file);
        open(my $in, '<:raw', $path) or return undef;
        local $/;
        my $text = <$in>;
        close $in;
        return defined $text ? $text : '';
    };
    my $toolchain_file = File::Spec->catfile($root, 'rust-toolchain.toml');
    my $channel;
    if (open(my $in, '<:raw', $toolchain_file)) {
        local $/;
        $channel = pinned_channel(scalar <$in>);
        close $in;
    }
    unless (defined $channel) {
        print STDERR "check-crate-licences: no channel = \"...\" line in $toolchain_file\n";
        exit 2;
    }
    my ($rows, $build_rows, $violations, $shipped) = eval { check($meta, $read_legal, $channel, $linked) };
    if ($@) {
        print STDERR "check-crate-licences: the cargo metadata cannot be checked: $@";
        exit 2;
    }
    if (@$violations) {
        print "check-crate-licences: $_\n" for @$violations;
        exit 1;
    }
    print "Rust crates linked into the shipped native libraries ($shipped shipped crate(s), $root):\n";
    print_table([@$rows, @$build_rows]);
    print "check-crate-licences: OK, " . scalar(@$rows) . " linked crate(s), " . scalar(@$build_rows)
        . " build-time crate(s)\n";
    exit 0;
}

# ---------------------------------------------------------------------------------------------------------
# --self-test: canned metadata, one case per rule, each rule shown passing and failing; also the command line
# and the cargo metadata call it leads to
# ---------------------------------------------------------------------------------------------------------
my $REGISTRY = 'registry+https://github.com/rust-lang/crates.io-index';

sub canned {
    my (%spec) = @_;
    my (@packages, @members, @nodes);
    for my $c (@{ $spec{crates} }) {
        my ($name, $version, $licence, %o) = @$c;
        my $id = $o{own} ? "path+file:///r/$name#$version" : "$REGISTRY#$name\@$version";
        my $manifest = $o{manifest} || ($o{own}
            ? "/r/modules/javafx.media/src/main/native-rust/$name/Cargo.toml"
            : "/home/u/.cargo/registry/src/index/$name-$version/Cargo.toml");
        my $kind = $o{kind} || ['lib'];
        push @packages, { id => $id, name => $name, version => $version, license => $licence,
            source => $o{own} ? undef : $REGISTRY, manifest_path => $manifest,
            targets => [{ name => $name, kind => $kind, crate_types => $o{types} || $kind }] };
        push @members, $id if $o{own};
    }
    my %id_of = map { $_->{name} => $_->{id} } @packages;
    for my $p (@packages) {
        my @deps;
        for my $edge (@{ $spec{edges} }) {
            my ($from, $to, @kinds) = @$edge;
            next unless $from eq $p->{name};
            my %dep = (name => $to, pkg => $id_of{$to});
            $dep{dep_kinds} = [map { { kind => ($_ eq 'normal' ? undef : $_), target => undef } } @kinds]
                unless "@kinds" eq 'no-dep-kinds';
            push @deps, \%dep;
        }
        push @nodes, { id => $p->{id}, deps => \@deps };
    }
    my $meta = { packages => \@packages, workspace_members => \@members, resolve => { nodes => \@nodes } };
    my $resolve = $spec{resolve} || '';
    delete $meta->{resolve} if $resolve eq 'absent';
    $meta->{resolve} = undef if $resolve eq 'null';
    $meta->{resolve}{nodes} = { map { $_->{id} => $_ } @nodes } if $resolve eq 'nodes-object';
    # Through the JSON decoder, as the real metadata goes
    return JSON::PP->new->decode(JSON::PP->new->encode($meta));
}

# Runs this script itself, so that the way from its command line to check() is tested too. The metadata goes in a
# temporary file, which is removed when this returns.
sub run_script {
    my ($meta, @args) = @_;
    my $json = File::Temp->new(TEMPLATE => 'check-crate-licences-XXXXXX', SUFFIX => '.json', TMPDIR => 1);
    binmode $json;
    print $json JSON::PP->new->encode($meta);
    close $json;
    open(my $out, '-|', $^X, abs_path(__FILE__), '--metadata', $json->filename, @args)
        or return (undef, "cannot run $^X: $!\n");
    local $/;
    my $output = <$out>;
    close $out;
    return ($? >> 8, defined $output ? $output : '');
}

sub self_test {
    my $own = $OWN_LICENCE;
    my @base = (['mylib', '0.0.0', $own, own => 1, types => ['staticlib', 'rlib']],
                ['jfx-rust-support', '0.0.0', $own, own => 1, types => ['rlib']]);
    my @base_edges = (['mylib', 'jfx-rust-support', 'normal']);
    # A canned pin: the real one is read from rust-toolchain.toml
    my $channel = '9.87.6';
    my $std = { 'rust-std.md' => "## The Rust Standard Library $channel\n\n### Notice\n" };
    my $listed = sub { my $md = join('', map { "## $_\n" } @_); return { %$std, 'rust-crates.md' => $md } };
    my $one_dep = sub {
        my ($licence, %o) = @_;
        return { crates => [@base, ['dep', '1.2.3', $licence, %o]],
                 edges => [@base_edges, ['mylib', 'dep', 'normal']] };
    };
    my @cases = (
        ['the repository\'s own crates pass with the exact licence', { crates => \@base, edges => \@base_edges },
            $std, [], qr/jfx-rust-support .*own crate/],
        ['an own crate with another licence fails',
            { crates => [$base[0], ['jfx-rust-support', '0.0.0', 'MIT', own => 1, types => ['rlib']]],
              edges => \@base_edges },
            $std, [qr/jfx-rust-support 0\.0\.0 is a crate of this repository .* not "MIT"/]],
        ['a shipped crate without the Classpath exception fails',
            { crates => [['mylib', '0.0.0', 'GPL-2.0-only', own => 1, types => ['staticlib']]], edges => [] },
            $std, [qr/mylib 0\.0\.0 is a crate of this repository .* not "GPL-2\.0-only"/]],
        ['an rlib-only member is no shipped crate: unlinked, it is not checked',
            { crates => [@base, ['tool', '0.0.0', 'Apache-2.0', own => 1, types => ['rlib']]],
              edges => \@base_edges },
            $std, []],
        ['a member with a staticlib target that the build links passes',
            { crates => \@base, edges => \@base_edges, linked => ['mylib'] },
            $std, [], qr/mylib .*own crate/],
        ['an rlib-only member that the build links fails',
            { crates => [['mylib', '0.0.0', $own, own => 1, types => ['rlib']], $base[1]],
              edges => \@base_edges, linked => ['mylib'] },
            $std, [qr/^mylib 0\.0\.0 is linked by the build, but its Cargo\.toml lists no staticlib crate type/]],
        ['a linked name that no package has fails',
            { crates => \@base, edges => \@base_edges, linked => ['mylib', 'nosuch'] },
            $std, [qr/^nosuch is linked by the build, but is no workspace member: .*staticlib crate type/]],
        ['a linked name of a third-party crate is no workspace member and fails',
            { crates => [@base, ['dep', '1.2.3', 'MIT']], edges => [@base_edges, ['mylib', 'dep', 'normal']],
              linked => ['dep'] },
            $listed->('dep 1.2.3'), [qr/^dep is linked by the build, but is no workspace member/]],
        ['a module that ships Rust without rust-std.md fails', { crates => \@base, edges => \@base_edges },
            {}, [qr{modules/javafx\.media/src/main/legal/rust-std\.md is missing}]],
        ['a shipped crate outside modules/<module>/src/main/native-rust fails',
            { crates => [['mylib', '0.0.0', $own, own => 1, types => ['staticlib'],
                manifest => '/r/buildtools/rust/mylib/Cargo.toml']], edges => [] },
            $std, [qr/mylib 0\.0\.0 has a staticlib target but is not in modules/]],
        (map { my $l = $_; ["$l passes", $one_dep->($l), $listed->('dep 1.2.3'), [], qr/dep +1\.2\.3 +\Q$l\E /] }
            qw(MIT BSD-2-Clause BSD-3-Clause ISC Zlib Unicode-3.0)),
        ['MIT OR Apache-2.0 passes, taken under MIT', $one_dep->('MIT OR Apache-2.0'), $listed->('dep 1.2.3'),
            [], qr/taken under MIT$/m],
        ['Apache-2.0 OR MIT passes, taken under MIT', $one_dep->('Apache-2.0 OR MIT'), $listed->('dep 1.2.3'),
            [], qr/taken under MIT$/m],
        ['MIT/Apache-2.0 (the old cargo form) passes, taken under MIT', $one_dep->('MIT/Apache-2.0'),
            $listed->('dep 1.2.3'), [], qr/taken under MIT$/m],
        ['(MIT OR Apache-2.0) AND Unicode-3.0 passes', $one_dep->('(MIT OR Apache-2.0) AND Unicode-3.0'),
            $listed->('dep 1.2.3'), [], qr/taken under MIT AND Unicode-3\.0$/m],
        (map { my $l = $_; ["$l fails", $one_dep->($l), $listed->('dep 1.2.3'),
                [qr/dep 1\.2\.3 is linked into javafx\.media and its licence is rejected/]] }
            'Apache-2.0', 'Apache-2.0 WITH LLVM-exception', 'GPL-3.0-only', 'GPL-3.0-or-later', 'LGPL-3.0-only',
            'AGPL-3.0-only', 'Foo-1.0', 'MIT AND Apache-2.0', 'MIT OR'),
        ['a missing licence fails', $one_dep->(undef), $listed->('dep 1.2.3'),
            [qr/dep 1\.2\.3 .* rejected: no licence/]],
        ['MPL-2.0 without a sign-off fails', $one_dep->('MPL-2.0'), $listed->('dep 1.2.3'),
            [qr/dep 1\.2\.3 is linked into javafx\.media under MPL-2\.0, which needs .*Maintainer sign-off/]],
        ['MPL-2.0 with a sign-off for that version passes', $one_dep->('MPL-2.0'),
            { %$std, 'rust-crates.md' => "## dep 1.2.3\n\nMaintainer sign-off: dep 1.2.3 by a maintainer\n" },
            [], qr/taken under MPL-2\.0 \(signed off\)/],
        ['MPL-2.0 with a sign-off for another version fails', $one_dep->('MPL-2.0'),
            { %$std, 'rust-crates.md' => "## dep 1.2.3\n\nMaintainer sign-off: dep 1.2.2 by a maintainer\n" },
            [qr/needs a line "Maintainer sign-off: dep 1\.2\.3/]],
        ['LGPL-2.1-only without a sign-off fails', $one_dep->('LGPL-2.1-only'), $listed->('dep 1.2.3'),
            [qr/under LGPL-2\.1-only, which needs/]],
        ['LGPL-2.1-or-later with a sign-off passes', $one_dep->('LGPL-2.1-or-later'),
            { %$std, 'rust-crates.md' => "## dep 1.2.3\nMaintainer sign-off: dep 1.2.3\n" }, [],
            qr/signed off/],
        ['a linked crate missing from rust-crates.md fails (no file)', $one_dep->('MIT'), $std,
            [qr{dep 1\.2\.3 is linked into javafx\.media but has no "## dep 1\.2\.3" heading}]],
        ['a linked crate listed with another version fails', $one_dep->('MIT'), $listed->('dep 1.2.4'),
            [qr/has no "## dep 1\.2\.3" heading/]],
        ['a normal dependency of a third-party crate is followed',
            { crates => [@base, ['a', '1.0.0', 'MIT'], ['b', '2.0.0', 'Apache-2.0']],
              edges => [@base_edges, ['mylib', 'a', 'normal'], ['a', 'b', 'normal']] },
            $listed->('a 1.0.0', 'b 2.0.0'), [qr/b 2\.0\.0 .* rejected/]],
        ['a proc macro and a build dependency are listed, not gated; a dev dependency is not followed',
            { crates => [@base, ['pm', '1.0.0', 'Apache-2.0', kind => ['proc-macro']],
                         ['bd', '1.0.0', 'GPL-3.0-only'], ['dd', '1.0.0', 'GPL-3.0-only'],
                         ['pmdep', '1.0.0', 'Apache-2.0']],
              edges => [@base_edges, ['mylib', 'pm', 'normal'], ['mylib', 'bd', 'build'],
                        ['mylib', 'dd', 'dev'], ['pm', 'pmdep', 'normal']] },
            $std, [],
            qr/^(?=.*\bbd\b.*build time only)(?=.*\bpm\b.*build time only)(?=.*\bpmdep\b.*build time)(?!.*\bdd\b)/s],
        ['a dependency that is both normal and build is linked and gated',
            { crates => [@base, ['nb', '1.0.0', 'Apache-2.0']],
              edges => [@base_edges, ['mylib', 'nb', 'normal', 'build']] },
            $listed->('nb 1.0.0'), [qr/nb 1\.0\.0 .* rejected/]],
        (map { my ($how, $what) = @$_;
               ["metadata whose $what cannot be checked (exit 2)",
                { crates => [@base, ['dep', '1.2.3', 'GPL-3.0-only']],
                  edges => [@base_edges, ['mylib', 'dep', 'normal']], resolve => $how },
                $listed->('dep 1.2.3'), { dies => qr/^it has no resolve graph/ }] }
            ['absent', 'resolve is missing'], ['null', 'resolve is null'],
            ['nodes-object', 'resolve.nodes is not an array']),
        ['metadata with an edge that has no dep_kinds cannot be checked (exit 2)',
            { crates => [@base, ['dep', '1.2.3', 'GPL-3.0-only']],
              edges => [@base_edges, ['mylib', 'dep', 'no-dep-kinds']] },
            $listed->('dep 1.2.3'), { dies => qr/^the edge from \S+mylib\S* to \S+dep\@1\.2\.3 has no dep_kinds/ }],
        ['metadata with dep_kinds on every edge is checked: its GPL-3.0 dependency is found',
            { crates => [@base, ['dep', '1.2.3', 'GPL-3.0-only']],
              edges => [@base_edges, ['mylib', 'dep', 'normal']] },
            $listed->('dep 1.2.3'), [qr/dep 1\.2\.3 .* rejected/]],
        ['a sign-off for dep 1.2.3-beta does not sign off dep 1.2.3', $one_dep->('MPL-2.0'),
            { %$std, 'rust-crates.md' => "## dep 1.2.3\n\nMaintainer sign-off: dep 1.2.3-beta by a maintainer\n" },
            [qr/needs a line "Maintainer sign-off: dep 1\.2\.3 \.\.\."/]],
        ['a sign-off for dep 1.2.3.4 does not sign off dep 1.2.3', $one_dep->('MPL-2.0'),
            { %$std, 'rust-crates.md' => "## dep 1.2.3\n\nMaintainer sign-off: dep 1.2.3.4\n" },
            [qr/needs a line "Maintainer sign-off: dep 1\.2\.3 \.\.\."/]],
        ['a sign-off for dep 1.2.3-beta signs off dep 1.2.3-beta',
            { crates => [@base, ['dep', '1.2.3-beta', 'MPL-2.0']], edges => [@base_edges, ['mylib', 'dep', 'normal']] },
            { %$std, 'rust-crates.md' => "## dep 1.2.3-beta\n\nMaintainer sign-off: dep 1.2.3-beta\n" },
            [], qr/dep 1\.2\.3-beta MPL-2\.0 javafx\.media taken under MPL-2\.0 \(signed off\)/],
        ['rust-std.md whose heading names the pinned channel passes', { crates => \@base, edges => \@base_edges },
            { 'rust-std.md' => "## The Rust Standard Library $channel\n\n## Another heading 1.0.0\n" }, []],
        ['rust-std.md whose heading names another toolchain fails', { crates => \@base, edges => \@base_edges },
            { 'rust-std.md' => "## The Rust Standard Library 9.87.5\n" },
            [qr{rust-std\.md: its heading "## The Rust Standard Library 9\.87\.5" does not name the Rust toolchain }]],
        ['rust-std.md whose heading names a longer version fails', { crates => \@base, edges => \@base_edges },
            { 'rust-std.md' => "## The Rust Standard Library ${channel}0\n" },
            [qr/does not name the Rust toolchain 9\.87\.6 /]],
        ['rust-std.md whose first heading does not name the channel fails, a later one does not count',
            { crates => \@base, edges => \@base_edges },
            { 'rust-std.md' => "## The Rust Standard Library\n\n## Version $channel\n" },
            [qr/its heading "## The Rust Standard Library" does not name/]],
        ['rust-std.md without a heading fails', { crates => \@base, edges => \@base_edges },
            { 'rust-std.md' => "The Rust Standard Library $channel\n" }, [qr/its heading \(none\) does not name/]],
    );

    my $failed = 0;
    my $n = 0;
    my @toolchains = (["[toolchain]\nchannel = \"$channel\"\n", $channel],
                      ["[toolchain]\nprofile = \"minimal\"\n", undef]);
    for my $toolchain (@toolchains) {
        my ($text, $want) = @$toolchain;
        $n++;
        my $got = pinned_channel($text);
        my $name = 'the pin is read from rust-toolchain.toml: '
            . (defined $want ? "channel $want" : 'none without a channel line');
        if ((defined $got ? $got : '(none)') eq (defined $want ? $want : '(none)')) {
            print "ok $n - $name\n";
        } else {
            $failed++;
            print "not ok $n - $name: got " . (defined $got ? $got : '(none)') . "\n";
        }
    }
    my @lookups = (['/c', '/c/bin', 'CARGO_HOME set'], ['', '/h/.cargo/bin', 'CARGO_HOME empty'],
                   [undef, '/h/.cargo/bin', 'CARGO_HOME unset']);
    for my $lookup (@lookups) {
        my ($cargo_home, $want, $what) = @$lookup;
        $n++;
        local %ENV = %ENV;
        $ENV{HOME} = $ENV{USERPROFILE} = '/h';
        if (defined $cargo_home) {
            $ENV{CARGO_HOME} = $cargo_home;
        } else {
            delete $ENV{CARGO_HOME};
        }
        my $got = cargo_hint_dir();
        my $name = "with $what, cargo is looked up after PATH in $want only";
        if (defined $got && $got eq $want) {
            print "ok $n - $name\n";
        } else {
            $failed++;
            print "not ok $n - $name: got " . (defined $got ? $got : '(none)') . "\n";
        }
    }
    my $options = sub {
        my ($opt) = @_;
        return 'the usage text' unless ref $opt;
        return join(' ', map { my $v = $opt->{$_}; "$_=" . (ref $v ? '[' . join(',', @$v) . ']' : $v) }
            sort keys %$opt);
    };
    my @command_lines = ([[qw(--cargo c --config f.toml --metadata m.json --linked a --linked b)],
                          { cargo => 'c', config => 'f.toml', metadata => 'm.json', linked => ['a', 'b'] }],
                         [[], { linked => [] }],
                         [['--self-test'], { self_test => 1, linked => [] }],
                         [['--linked'], undef],
                         [['--config'], undef],
                         [[qw(--linked a --config)], undef]);
    for my $command_line (@command_lines) {
        my ($args, $want) = @$command_line;
        $n++;
        my $got = eval { parse_args(@$args) };
        my $stopped = $@;
        my $name = "the command line \"@$args\" gives " . $options->($want);
        if ($options->($got) eq $options->($want) && (ref $got || $stopped eq $USAGE)) {
            print "ok $n - $name\n";
        } else {
            $failed++;
            print "not ok $n - $name: got " . $options->($got) . "\n";
        }
    }
    my @metadata_commands = ([['c', 'f.toml'], 'c metadata --locked --offline --config f.toml --format-version 1'],
                             [['c', undef], 'c metadata --locked --offline --format-version 1']);
    for my $metadata_command (@metadata_commands) {
        my ($args, $want) = @$metadata_command;
        $n++;
        my $got = join(' ', metadata_command(@$args));
        my $name = 'cargo metadata runs as "' . $want . '"';
        if ($got eq $want) {
            print "ok $n - $name\n";
        } else {
            $failed++;
            print "not ok $n - $name: got \"$got\"\n";
        }
    }
    for my $case (@cases) {
        my ($name, $spec, $legal, $expected, $report_re) = @$case;
        $n++;
        my $meta = canned(%$spec);
        my ($rows, $build_rows, $violations)
            = eval { check($meta, sub { $legal->{ $_[1] } }, $channel, $spec->{linked}) };
        my $died = $@;
        if (ref $expected eq 'HASH') {
            if ($died =~ $expected->{dies}) {
                chomp $died;
                print "ok $n - $name (exit 2: $died)\n";
            } else {
                $failed++;
                print "not ok $n - $name: expected the check to stop with $expected->{dies}, got "
                    . ($died ? $died : "no stop\n");
            }
            next;
        }
        if ($died) {
            $failed++;
            print "not ok $n - $name: the check stopped: $died";
            next;
        }
        my $report = join('', map { join(' ', @$_) . "\n" } @$rows, @$build_rows);
        my @problems;
        if (@$violations != @$expected) {
            push @problems, scalar(@$violations) . " violation(s), expected " . scalar(@$expected);
        }
        for my $i (0 .. $#$expected) {
            push @problems, "violation " . ($i + 1) . " does not match $expected->[$i]"
                unless defined $violations->[$i] && $violations->[$i] =~ $expected->[$i];
        }
        push @problems, "report does not match $report_re" if $report_re && $report !~ $report_re;
        if (@problems) {
            $failed++;
            print "not ok $n - $name: " . join('; ', @problems) . "\n";
            print "    violation: $_\n" for @$violations;
            print "    report: $_\n" for split /\n/, $report;
        } else {
            my $shown = @$violations ? "fails: $violations->[0]" : 'passes';
            print "ok $n - $name ($shown)\n";
        }
    }
    my $violation = 'check-crate-licences: mylib 0.0.0 is linked by the build, but its Cargo.toml lists no staticlib '
        . 'crate type';
    my @runs = (['the script run with --linked mylib, an rlib-only member, exits 1 with the --linked violation',
                 [['mylib', '0.0.0', $own, own => 1, types => ['rlib']], $base[1]], 1],
                ['the script run with --linked mylib, a member with a staticlib target, prints no such violation',
                 \@base, 0]);
    for my $run (@runs) {
        my ($name, $crates, $fails) = @$run;
        $n++;
        my ($status, $output) = run_script(canned(crates => $crates, edges => \@base_edges), '--linked', 'mylib');
        my $printed = $output =~ /^\Q$violation\E/m;
        if (defined $status && ($fails ? $printed && $status == 1 : !$printed && $status <= 1)) {
            print "ok $n - $name (exit $status)\n";
        } else {
            $failed++;
            print "not ok $n - $name: exit status " . (defined $status ? $status : '(none)') . "\n";
            print "    output: $_\n" for split /\n/, $output;
        }
    }
    print $failed ? "check-crate-licences --self-test: $failed of $n cases FAILED\n"
                  : "check-crate-licences --self-test: all $n cases passed\n";
    exit($failed ? 1 : 0);
}

# ---------------------------------------------------------------------------------------------------------
my $opt = eval { parse_args(@ARGV) };
unless ($opt) {
    print STDERR $@;
    exit 2;
}
$opt->{self_test} ? self_test() : real_check($opt->{cargo}, $opt->{metadata}, $opt->{config}, $opt->{linked});
