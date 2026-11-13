# This machine's own configuration, the agent's file. What every machine
# shares comes from rragent's base (flake.nix beside this file); what
# this machine has on top of it is said here and nowhere else, so that
# this folder and /home/sprite are all it takes to make the machine
# again somewhere else.
#
# After a change: rragent-system apply
{ pkgs, ... }:

{
  # Programs to keep, by their nixpkgs names (nix search nixpkgs <word>).
  environment.systemPackages = with pkgs; [
  ];
}
