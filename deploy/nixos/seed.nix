# What /etc/nixos starts as on a new machine: machine/flake.nix, the
# agent's local.nix and a lock that pins both inputs, nixpkgs as this
# repository's own lock has it and rragent at the commit the base was
# built from. So the first `rragent-system apply` on a machine builds
# the system its image booted, and downloads nothing.
#
# The commit is known when the base is built from a clean checkout or
# from GitHub (.github/actions/sprite-nixos-image builds from
# github:storrito/rragent/<sha>, so the hash in the lock is the one a
# machine's fetch will compute). A build from a tree with changes has no
# commit to name: its seed locks nixpkgs alone, and the machine locks
# rragent at its first build, at main's newest commit.
{
  pkgs,
  source,
  lock,
}:

let
  rragent = {
    inputs.nixpkgs = [ "nixpkgs" ];
    locked = {
      type = "github";
      owner = "storrito";
      repo = "rragent";
      dir = "deploy/nixos";
      inherit (source) rev narHash lastModified;
    };
    original = {
      type = "github";
      owner = "storrito";
      repo = "rragent";
      dir = "deploy/nixos";
    };
  };
  machineLock = {
    version = 7;
    root = "root";
    nodes = {
      nixpkgs = lock.nodes.nixpkgs;
      root.inputs = {
        nixpkgs = "nixpkgs";
      }
      // pkgs.lib.optionalAttrs (source.rev != null) { rragent = "rragent"; };
    }
    // pkgs.lib.optionalAttrs (source.rev != null) { inherit rragent; };
  };
  lockFile = pkgs.writeText "flake.lock" (builtins.toJSON machineLock);
in
pkgs.runCommand "rragent-machine-seed" { } ''
  mkdir -p $out
  cp ${./machine/flake.nix} $out/flake.nix
  cp ${./machine/local.nix} $out/local.nix
  ${pkgs.jq}/bin/jq . ${lockFile} > $out/flake.lock
''
