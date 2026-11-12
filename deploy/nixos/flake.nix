{
  description = "rragent on a NixOS Sprite: the base every machine shares, and the image a machine starts from";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

  outputs =
    { self, nixpkgs }:
    let
      system = "x86_64-linux";
      version = nixpkgs.lib.removeSuffix "\n" (builtins.readFile ./VERSION);

      # The commit this base is built from, which a new machine's
      # /etc/nixos pins rragent to (seed.nix); null from a tree with
      # changes that are not committed.
      source = {
        rev = self.rev or null;
        narHash = self.narHash or null;
        lastModified = self.lastModified or null;
      };

      # The base: sprite.nix, with its version and the seed of a new
      # machine's /etc/nixos. A machine imports it from its own flake
      # (machine/flake.nix) beside its local.nix.
      base =
        { pkgs, ... }:
        {
          imports = [ ./sprite.nix ];
          rragent.imageVersion = version;
          rragent.seed = import ./seed.nix {
            inherit pkgs source;
            lock = builtins.fromJSON (builtins.readFile ./flake.lock);
          };
        };

      # The system the image boots: the base and the local.nix a new
      # machine starts with, the same two modules the machine's own
      # flake builds from, so its first build is this system again.
      machine = nixpkgs.lib.nixosSystem {
        inherit system;
        modules = [
          base
          ./machine/local.nix
        ];
      };
    in
    {
      nixosModules.sprite = base;

      nixosConfigurations.sprite = machine;

      packages.${system} = {
        # The image's root filesystem, a tarball for `docker import`
        # (.github/actions/sprite-nixos-image).
        tarball = machine.config.system.build.tarball;
        toplevel = machine.config.system.build.toplevel;
      };
    };
}
