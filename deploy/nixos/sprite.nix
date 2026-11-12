# The base of a NixOS Sprite that runs rragent: everything the machine
# needs around the bundle, the same things deploy/image/Dockerfile puts
# on the Ubuntu image, said as a NixOS module. A machine imports it from
# its own /etc/nixos/flake.nix (machine/flake.nix here) beside its
# local.nix, the agent's file; so a fix of ours reaches a machine with
# its next `rragent-system upgrade`, and what the agent added stays in
# the one file it owns.
#
# The Sprite runs the image's systemd as its init (config.init
# "systemd"); networking, DNS and the root mount stay with the platform,
# so nothing here touches them. rragent is a systemd unit on port 8080,
# the port every Sprite's URL routes to; the Sprite needs no service
# definition of its own.
{
  config,
  lib,
  pkgs,
  ...
}:

let
  cfg = config.rragent;

  home = "/home/sprite";

  # What a process of the machine finds its programs by: the person's
  # ~/.local first (npm's global prefix, where the HyperFrames CLI and
  # nbb land), then NixOS's own places, then the Sprite's. One string,
  # because the image names it too: a Sprite's exec takes its PATH from
  # the image's ENV, not from the login profile
  # (.github/actions/sprite-nixos-image; test/rragent/nixos_test.cljs
  # holds the two together).
  path = "/home/sprite/.local/bin:/run/wrappers/bin:/home/sprite/.nix-profile/bin:/etc/profiles/per-user/sprite/bin:/nix/var/nix/profiles/default/bin:/run/current-system/sw/bin:/.sprite/bin";

  # The Ubuntu image's start.sh, one file for both kinds of machine,
  # with the PATH of a NixOS machine in place of Ubuntu's. The line is
  # replaced by its exact text: a start.sh that changes it fails this
  # build instead of starting rragent without its programs
  # (test/rragent/nixos_test.cljs says so before a build does).
  ubuntuPath = "export PATH=/home/sprite/.local/bin:/usr/local/share/mise/shims:/.sprite/bin:/usr/local/bin:/usr/bin:/bin";
  start = pkgs.runCommand "rragent-start" { } ''
    mkdir -p $out/bin
    substitute ${../image/start.sh} $out/bin/rragent-start \
      --replace-fail '#!/bin/bash' '#!${pkgs.bash}/bin/bash' \
      --replace-fail '${ubuntuPath}' 'export PATH=${path}'
    chmod +x $out/bin/rragent-start
  '';

  # `rragent-system`: how the agent changes its machine.
  system = pkgs.runCommand "rragent-system" { } ''
    mkdir -p $out/bin
    substitute ${./rragent-system.sh} $out/bin/rragent-system \
      --replace-fail '#!/bin/bash' '#!${pkgs.bash}/bin/bash'
    chmod +x $out/bin/rragent-system
  '';

  # The Ubuntu reflex, answered with what works here.
  apt = pkgs.writeShellScriptBin "apt-get" ''
    echo "This machine runs NixOS, there is no apt." >&2
    echo "For one command:  nix shell nixpkgs#<package> -c <command>" >&2
    echo "To keep a program: add it to environment.systemPackages in /etc/nixos/local.nix, then run: rragent-system apply" >&2
    echo "To find a package: nix search nixpkgs <word>" >&2
    exit 100
  '';

  # The name rragent's prompt and the skills call the browser by.
  chrome = pkgs.writeShellScriptBin "google-chrome" ''
    exec ${pkgs.chromium}/bin/chromium "$@"
  '';

  # The HyperFrames CLI and nbb, npm packages: installed into the
  # person's ~/.local at these versions, with the HyperFrames skills
  # the template's notes send the agent to. Data of the home folder, as
  # the bundle and the skills clone are; a machine made anew installs
  # them again.
  tools = pkgs.writeShellScript "rragent-tools" ''
    export PATH=${path}
    export HOME=${home}
    want_hyperframes=${cfg.hyperframesVersion}
    want_nbb=${cfg.nbbVersion}
    have() { "$1" --version 2>/dev/null | grep -qF "$2"; }
    if have hyperframes "$want_hyperframes" && have nbb "$want_nbb"; then
      exit 0
    fi
    PUPPETEER_SKIP_DOWNLOAD=1 npm install -g --prefix ${home}/.local --ignore-scripts --no-fund --no-audit --progress=false \
      "hyperframes@$want_hyperframes" "@hyperframes/player@$want_hyperframes" "nbb@$want_nbb" || exit 1
    hyperframes skills && hyperframes skills check
  '';
in
{
  options.rragent = {
    seed = lib.mkOption {
      type = lib.types.nullOr lib.types.package;
      default = null;
      description = "What /etc/nixos starts as on a machine that has none: the machine's flake, its lock and the agent's local.nix.";
    };
    imageVersion = lib.mkOption {
      type = lib.types.str;
      default = "0.0.0";
      description = "The version of the base, deploy/nixos/VERSION.";
    };
    hyperframesVersion = lib.mkOption {
      type = lib.types.str;
      default = "0.8.221";
      description = "The HyperFrames CLI's version, as the Ubuntu image's Dockerfile names it.";
    };
    nbbVersion = lib.mkOption {
      type = lib.types.str;
      default = "1.6.214";
      description = "nbb's version, as the Ubuntu image's Dockerfile names it.";
    };
  };

  config = {
    # A Sprite brings the kernel, the network, DNS and the root mount.
    boot.isContainer = true;
    system.name = lib.mkDefault "rragent-sprite";
    networking.hostName = lib.mkDefault "";
    networking.useDHCP = false;
    networking.useHostResolvConf = false;
    networking.resolvconf.enable = false;
    networking.firewall.enable = false;
    services.resolved.enable = false;
    services.timesyncd.enable = false;
    systemd.oomd.enable = false;
    services.nscd.enable = false;
    system.nssModules = lib.mkForce [ ];

    # A Sprite starts its systemd by /sbin/init and takes only a file
    # named systemd for one ("/sbin/init in its image is not systemd"
    # otherwise). NixOS's init is a script that sets the system up, the
    # activation, and then runs systemd. So every system carries that
    # script once more under the name the platform looks for, and
    # /sbin/init is a link to it: the image's to the first system's, and
    # after every switch to the new system's, so that the next cold
    # start boots what the last switch made.
    system.systemBuilderCommands = ''
      mkdir -p $out/sprite-init/lib/systemd
      {
        echo '#!${pkgs.runtimeShell}'
        echo "exec $out/init \"\$@\""
      } > $out/sprite-init/lib/systemd/systemd
      chmod +x $out/sprite-init/lib/systemd/systemd
    '';
    system.build.installBootLoader = pkgs.writeShellScript "install-sbin-init" ''
      ${pkgs.coreutils}/bin/ln -sfn "$1/sprite-init/lib/systemd/systemd" /sbin/init
    '';

    # The image's store paths, made known to Nix at the first boot.
    systemd.services.register-nix-paths = {
      description = "Register the image's Nix store paths";
      unitConfig = {
        DefaultDependencies = false;
        ConditionPathExists = "/nix-path-registration";
      };
      wantedBy = [ "sysinit.target" ];
      before = [
        "sysinit.target"
        "shutdown.target"
        "nix-daemon.socket"
        "nix-daemon.service"
      ];
      after = [ "local-fs.target" ];
      conflicts = [ "shutdown.target" ];
      restartIfChanged = false;
      serviceConfig = {
        Type = "oneshot";
        RemainAfterExit = true;
      };
      script = ''
        ${lib.getExe' config.nix.package.out "nix-store"} --load-db < /nix-path-registration
        rm /nix-path-registration
        ${lib.getExe' config.nix.package.out "nix-env"} -p /nix/var/nix/profiles/system --set /run/current-system
      '';
    };

    system.build.tarball = pkgs.callPackage (pkgs.path + "/nixos/lib/make-system-tarball.nix") {
      fileName = "rragent-sprite-nixos";
      extraArgs = "--owner=0";
      # docker import takes the tar as it is and the push compresses it
      compressCommand = "cat";
      compressionExtension = "";
      extraInputs = [ ];
      storeContents = [
        {
          object = config.system.build.toplevel;
          symlink = "none";
        }
      ];
      contents = [
        {
          source = config.system.build.toplevel + "/etc/os-release";
          target = "/etc/os-release";
        }
      ];
      # a script: the builder runs this as one command, not as shell text
      extraCommands = pkgs.writeScript "sprite-rootfs.sh" ''
        #!${pkgs.runtimeShell}
        mkdir -p proc sys dev tmp sbin
        ln -s ${config.system.build.toplevel}/sprite-init/lib/systemd/systemd sbin/init
      '';
    };

    # The one user everything runs as, the Sprites base image's name and
    # id for it; root through sudo, without a password, as there.
    users.mutableUsers = false;
    users.allowNoPasswordLogin = true;
    users.users.sprite = {
      isNormalUser = true;
      uid = 1001;
      group = "users";
      extraGroups = [ "wheel" ];
      home = home;
      createHome = true;
      shell = pkgs.bashInteractive;
    };
    security.sudo.wheelNeedsPassword = false;

    nixpkgs.config.allowUnfree = lib.mkDefault false;
    nix.settings.experimental-features = [
      "nix-command"
      "flakes"
    ];
    nix.settings.trusted-users = [
      "root"
      "sprite"
    ];
    # Generations and downloads nobody refers to any more leave the disk.
    nix.gc = {
      automatic = true;
      dates = "weekly";
      options = "--delete-older-than 14d";
    };

    # Programs that were built for another Linux (a downloaded binary, a
    # browser Puppeteer fetched, a Python wheel's library) find a loader
    # and the usual libraries here.
    programs.nix-ld.enable = true;
    programs.nix-ld.libraries = with pkgs; [
      stdenv.cc.cc.lib
      zlib
      openssl
      curl
      expat
      glib
      nss
      nspr
      dbus
      atk
      at-spi2-atk
      at-spi2-core
      cups
      libdrm
      libgbm
      libxkbcommon
      pango
      cairo
      alsa-lib
      fontconfig
      freetype
      libx11
      libxcomposite
      libxdamage
      libxext
      libxfixes
      libxrandr
      libxcb
    ];
    programs.command-not-found.enable = false;

    # The HyperFrames CLI and Puppeteer take the machine's browser
    # instead of downloading one: a downloaded Chrome runs here (nix-ld),
    # but HyperFrames checks it with ldd first, which knows nothing of
    # nix-ld and refuses it. rragent's own render says the same per call
    # (templates/hyperframes/render).
    environment.variables = {
      HYPERFRAMES_BROWSER_PATH = "/run/current-system/sw/bin/google-chrome";
      PRODUCER_HEADLESS_SHELL_PATH = "/run/current-system/sw/bin/google-chrome";
      PUPPETEER_EXECUTABLE_PATH = "/run/current-system/sw/bin/google-chrome";
      PUPPETEER_SKIP_DOWNLOAD = "1";
    };

    fonts.packages = with pkgs; [
      noto-fonts
      noto-fonts-color-emoji
      liberation_ttf
    ];

    environment.systemPackages = with pkgs; [
      # what rragent and its templates need (the Dockerfile's list)
      nodejs_26
      ffmpeg
      chromium
      chrome
      git
      curl
      # what start.sh and a bot's bash reach for
      coreutils
      gnutar
      gzip
      unzip
      zip
      jq
      ripgrep
      file
      which
      python3
      # this machine's own
      start
      system
      apt
      (pkgs.runCommand "apt" { } ''
        mkdir -p $out/bin
        ln -s ${apt}/bin/apt-get $out/bin/apt
      '')
    ];

    # A script that begins with #!/bin/bash runs here too.
    systemd.tmpfiles.rules = [
      "L+ /bin/bash - - - - /run/current-system/sw/bin/bash"
      "d ${home}/rragent 0755 sprite users -"
      "d /var/lib/rragent-system 0755 root root -"
    ];

    environment.etc."rragent/machine.md".source = ./MACHINE.md;
    environment.etc."rragent/base-version".text = cfg.imageVersion + "\n";

    # /etc/nixos of a machine that has none yet: the seed, the agent's
    # from then on, a git repository so that every change of the machine
    # is a commit.
    system.activationScripts.rragentSeed = lib.mkIf (cfg.seed != null) (
      lib.stringAfter
        [
          "users"
          "groups"
        ]
        ''
          if [ ! -e /etc/nixos/flake.nix ]; then
            mkdir -p /etc/nixos
            cp -r --no-preserve=mode,ownership ${cfg.seed}/. /etc/nixos/
            chown -R sprite:users /etc/nixos
            ${pkgs.util-linux}/bin/runuser -u sprite -- ${pkgs.bash}/bin/bash -c '
              cd /etc/nixos &&
              ${pkgs.git}/bin/git init -q -b main &&
              ${pkgs.git}/bin/git config user.name rragent &&
              ${pkgs.git}/bin/git config user.email rragent@localhost &&
              ${pkgs.git}/bin/git add -A &&
              ${pkgs.git}/bin/git commit -q -m "The machine as its image made it"
            ' || echo "rragent: /etc/nixos was seeded, the git repository was not made" >&2
          fi
        ''
    );

    systemd.services.rragent = {
      description = "rragent";
      wantedBy = [ "multi-user.target" ];
      # The agent switches the system from inside this unit. A switch
      # that restarted it would end the agent in the middle of its own
      # command, so the unit takes a new system at its next start.
      restartIfChanged = false;
      stopIfChanged = false;
      unitConfig.StartLimitIntervalSec = 0;
      environment.RRAGENT_MACHINE_PROMPT = "/etc/rragent/machine.md";
      serviceConfig = {
        User = "sprite";
        Group = "users";
        WorkingDirectory = "${home}/rragent";
        # /run/rragent/started-on: the system this start runs on, which
        # the watch takes as good once rragent answers
        RuntimeDirectory = "rragent";
        RuntimeDirectoryPreserve = "yes";
        ExecStart = pkgs.writeShellScript "rragent" ''
          . /etc/set-environment
          ${pkgs.coreutils}/bin/readlink -f /run/current-system > /run/rragent/started-on
          exec ${start}/bin/rragent-start
        '';
        Restart = "always";
        RestartSec = 5;
      };
    };

    systemd.services.rragent-tools = {
      description = "The HyperFrames CLI and nbb in the person's ~/.local";
      wantedBy = [ "multi-user.target" ];
      wants = [ "network-online.target" ];
      after = [ "network-online.target" ];
      serviceConfig = {
        Type = "oneshot";
        RemainAfterExit = true;
        User = "sprite";
        Group = "users";
        ExecStart = tools;
        Restart = "on-failure";
        RestartSec = 30;
      };
    };

    # The rollback nobody has to ask for: a system rragent does not
    # answer on is replaced by the last one it did answer on.
    systemd.services.rragent-system-watch = {
      description = "Back to the last system rragent answered on, when it does not answer on this one";
      serviceConfig = {
        Type = "oneshot";
        ExecStart = "${system}/bin/rragent-system watch";
      };
      path = [
        config.nix.package
        pkgs.curl
        pkgs.coreutils
        pkgs.systemd
      ];
    };
    systemd.timers.rragent-system-watch = {
      wantedBy = [ "timers.target" ];
      timerConfig = {
        OnBootSec = "20s";
        OnUnitInactiveSec = "5min";
      };
    };

    documentation.enable = lib.mkDefault false;
    system.stateVersion = lib.mkDefault "26.05";
  };
}
