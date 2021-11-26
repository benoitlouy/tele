{
  nixpkgs = fetchTarball {
    name   = "nixos-unstable-2021-11-22";
    url    = "https://github.com/NixOS/nixpkgs/archive/98747f27ecfe.tar.gz";
    sha256 = "04ss525ns5qqlggrdhvc6y4hqmshylda9yd0y99ddliyn15wmf27";
  };

  sbt-derivation = fetchTarball {
    name   = "sbt-derivation-2020-10-08";
    url    = "https://github.com/zaninime/sbt-derivation/archive/9666b2b.tar.gz";
    sha256 = "17r74avh4i3llxbskfjhvbys3avqb2f26pzydcdkd8a9k204rg9z";
  };
}
