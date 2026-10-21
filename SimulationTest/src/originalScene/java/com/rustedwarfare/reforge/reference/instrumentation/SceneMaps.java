package com.rustedwarfare.reforge.reference.instrumentation;

/** Fixed hashes generated from approved O.v1 prepared inputs; no map/image bytes embedded. */
final class SceneMaps {
    static String hash(String id) {
        switch(id) {
            case "OM00":return "77d698e970c75ec3cd572bbc03db83e026d4ee856cf785989387cb7667eb0d7a";
            case "OM01E":return "77d698e970c75ec3cd572bbc03db83e026d4ee856cf785989387cb7667eb0d7a";
            case "OM01W":return "77d698e970c75ec3cd572bbc03db83e026d4ee856cf785989387cb7667eb0d7a";
            case "OM02S":return "77d698e970c75ec3cd572bbc03db83e026d4ee856cf785989387cb7667eb0d7a";
            case "OM02N":return "77d698e970c75ec3cd572bbc03db83e026d4ee856cf785989387cb7667eb0d7a";
            case "OM03SE":return "77d698e970c75ec3cd572bbc03db83e026d4ee856cf785989387cb7667eb0d7a";
            case "OM03NW":return "77d698e970c75ec3cd572bbc03db83e026d4ee856cf785989387cb7667eb0d7a";
            case "OM04":return "77d698e970c75ec3cd572bbc03db83e026d4ee856cf785989387cb7667eb0d7a";
            case "OM05":return "77d698e970c75ec3cd572bbc03db83e026d4ee856cf785989387cb7667eb0d7a";
            case "OM06":return "77d698e970c75ec3cd572bbc03db83e026d4ee856cf785989387cb7667eb0d7a";
            case "OM07":return "f838039aca606d0b7ec2aba64eda871fbd1696deb2a7ae24587c3da09b6e1db3";
            case "OM08":return "b76e3ab93c1f828022d94f75faad7341f74931c3b5e1e007555523f7b15d3777";
            default:throw new IllegalArgumentException("scene-map-outside-approved-cases");
        }
    }
}
