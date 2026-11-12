## This machine runs NixOS

This machine is not Ubuntu. There is no `apt`, programs are not under
`/usr/bin`, and most of `/etc` cannot be written: the whole system is
built from one description, the folder `/etc/nixos`, and that folder is
yours to keep. Whatever the machine has beyond its base is said there,
so that the folder and `/home/sprite` are all it takes to make this
machine again somewhere else.

A program for one command: `nix shell nixpkgs#<package> -c <command>`.
Nothing stays installed. `nix search nixpkgs <word>` finds a package;
the names differ from Ubuntu's (`vips`, not `libvips-tools`).

A program to keep, a font, a service that runs by itself, a setting
that would be a file under `/etc`: say it in `/etc/nixos/local.nix`,
then run `rragent-system apply "<what changed, in a few words>"`. It
builds the new system beside the running one, switches to it, and goes
back by itself when node, the browser or rragent do not run on it; then
it commits the change in `/etc/nixos`. A build that fails changes
nothing: read the error, fix the file, apply again.

Never `nix profile install`, `nix-env -i`, `sudo make install` or a file
written under `/etc` by hand: what is not in `/etc/nixos` is not part of
the machine and is gone when it is made again. `npm install` and a
Python venv work as everywhere, inside your working directory.

A downloaded program that answers "No such file or directory" though
the file is there was built for another Linux. Most run anyway; when
one does not, take the program from nixpkgs instead.

`rragent-system upgrade` takes the newest packages of the NixOS release
and the newest base of rragent, the security fixes among them, and goes
back by itself like `apply`. Run it when the person asks for it or a
schedule does, never in the middle of other work. `rragent-system
rollback` undoes the last change. `rragent-system status` says what
runs and what the last changes were.

`/etc/nixos/flake.nix` names the NixOS release and rragent's base;
`flake.lock` pins both. Leave them to `rragent-system upgrade`, unless
the person asks for a newer NixOS release: that is the one line
`nixpkgs.url` in `flake.nix`, then `rragent-system upgrade`.

When `/var/lib/rragent-system/rolled-back` exists, the machine went back
to an older system by itself, because rragent did not answer on a newer
one. Tell the person in one sentence, read the file, find what broke
the newer system, and remove the file once `/etc/nixos` is fixed or the
change reverted.
