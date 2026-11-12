{
  description = "This machine: rragent's base and what its agent added";

  # nixpkgs is the NixOS release the machine runs; a newer release is a
  # change of this line. rragent is the base every machine shares
  # (github.com/storrito/rragent, deploy/nixos). flake.lock beside this
  # file pins both to a commit: `rragent-system upgrade` moves the pins,
  # nothing else does.
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";
    rragent.url = "github:storrito/rragent?dir=deploy/nixos";
    rragent.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs =
    { nixpkgs, rragent, ... }:
    {
      nixosConfigurations.sprite = nixpkgs.lib.nixosSystem {
        system = "x86_64-linux";
        modules = [
          rragent.nixosModules.sprite
          ./local.nix
        ];
      };
    };
}
