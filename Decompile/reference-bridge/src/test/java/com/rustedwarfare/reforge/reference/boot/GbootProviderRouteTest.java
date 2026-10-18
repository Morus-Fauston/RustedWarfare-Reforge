package com.rustedwarfare.reforge.reference.boot;

import java.io.File;
import java.util.HashMap;
import com.rustedwarfare.reforge.reference.io.FatalLatch;

/** Pure public metadata/map/lexical gates. Every scenario runs in an independent JVM. */
public final class GbootProviderRouteTest {
    private static final String AUTHORITY = "com.corrodinggames.rts.fileProvider";
    private interface Action { void run(); }
    private static void check(boolean accepted, String detail) {
        if (!accepted) throw new AssertionError(detail);
    }
    private static FatalLatch.Failure denied(String code, Action action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (FatalLatch.Failure error) {
            check(code.equals(error.code()), "Wrong code: " + error.code());
            return error;
        }
    }
    public static void main(String[] args) {
        String scenario = args[0];
        if (scenario.equals("metadata-before-roots")) {
            GbootBootstrap.requireProviderAuthorityMetadata(AUTHORITY);
            denied("roots-not-ready", () -> GbootBootstrap.providerRootFile());
        } else if (scenario.equals("metadata-invalid")) {
            FatalLatch.Failure first = denied("invalid-provider-authority",
                () -> GbootBootstrap.requireProviderAuthorityMetadata("foreign.authority"));
            check(first == denied("invalid-provider-authority",
                () -> GbootBootstrap.requireProviderAuthorityMetadata(AUTHORITY)), "first metadata failure identity");
        } else if (scenario.equals("root-map-pure")) {
            File root = new File("/owned/nonexistent-field-only-root");
            HashMap<String, File> roots = new HashMap<String, File>();
            roots.put("external_files", root);
            GbootBootstrap.requireProviderRootMap(roots, new File(root.getPath()));
            check(roots.size() == 1 && roots.get("external_files") == root, "map remains unmodified");
        } else if (scenario.equals("root-map-invalid")) {
            HashMap<String, File> roots = new HashMap<String, File>();
            File original = new File("/field-only/wrong");
            roots.put("external_files", original);
            denied("provider-root-mismatch", () -> GbootBootstrap.requireProviderRootMap(roots, new File("/field-only/right")));
            check(roots.size() == 1 && roots.get("external_files") == original, "invalid map cannot be rebound");
        } else if (scenario.equals("root-map-extra-key")) {
            HashMap<String, File> roots = new HashMap<String, File>();
            roots.put("external_files", new File("/field-only/root"));
            roots.put("other", new File("/field-only/root"));
            denied("provider-root-mismatch", () -> GbootBootstrap.requireProviderRootMap(roots, new File("/field-only/root")));
            check(roots.size() == 2, "extra map fields remain untouched");
        } else if (scenario.equals("metadata-null")) {
            denied("invalid-provider-authority", () -> GbootBootstrap.requireProviderAuthorityMetadata(null));
        } else if (scenario.equals("metadata-multiple")) {
            denied("invalid-provider-authority", () -> GbootBootstrap.requireProviderAuthorityMetadata(AUTHORITY + ";foreign"));
        } else if (scenario.equals("ordinary-routes")) {
            String relative = "assets/ordinary.bin";
            String absolute = "/nonexistent/ordinary-private.bin";
            GbootBootstrap.rejectUnsupportedIoRoute(relative);
            GbootBootstrap.rejectUnsupportedIoRoute(absolute);
            check(relative.equals("assets/ordinary.bin") && absolute.equals("/nonexistent/ordinary-private.bin"), "lexical pass never rewrites input");
            GbootBootstrap.requireProviderAuthorityMetadata(AUTHORITY);
        } else if (scenario.equals("unsupported-return")) {
            RuntimeException returned = GbootBootstrap.unsupportedRouteFailure("public-literal-operation");
            check(returned instanceof FatalLatch.Failure && ((FatalLatch.Failure)returned).code().equals("unsupported-route"), "deny returns shared Failure, not null");
            check(returned == denied("unsupported-route", () -> GbootBootstrap.unsupportedRouteFailure("second")), "same first failure after return and throw");
        } else if (scenario.equals("prior-fatal-route")) {
            FatalLatch.Failure first = denied("invalid-provider-authority", () -> GbootBootstrap.requireProviderAuthorityMetadata("foreign"));
            check(first == denied("invalid-provider-authority", () -> GbootBootstrap.rejectUnsupportedIoRoute("x.rwmod/a")), "route cannot replace first failure");
            check(first == denied("invalid-provider-authority", () -> GbootBootstrap.unsupportedRouteFailure(null)), "unconditional deny checks prior fatal before argument work");
        } else if (scenario.equals("reject-marker")) {
            String[] paths = {"a.rwmod/x", "a.rwmod\\x", "a.rwmod", "x.[saflink]/a", "x.[saflink]\\a", "x.[saflink]", "content://foreign/a", "/saf-virtual/x", "MOD|x/a", "NEW_PATH|maps2/x"};
            String path = paths[Integer.parseInt(args[1])];
            FatalLatch.Failure first = denied("unsupported-route", () -> GbootBootstrap.rejectUnsupportedIoRoute(path));
            check(first == denied("unsupported-route", () -> GbootBootstrap.rejectUnsupportedIoRoute("ordinary")), "first fatal identity after marker");
        } else if (scenario.equals("invalid-route")) {
            String[] paths = {null, "", "has\\ambiguous", "nul\0path"};
            denied("invalid-path", () -> GbootBootstrap.rejectUnsupportedIoRoute(paths[Integer.parseInt(args[1])]));
        } else if (scenario.equals("root-gates")) {
            String operation = args[1];
            denied("roots-not-ready", () -> {
                if (operation.equals("file")) GbootBootstrap.checkProviderFile(new File("/unverified/no-file"));
                else if (operation.equals("uri")) GbootBootstrap.providerFileForUri(null, "foreign");
                else if (operation.equals("open")) GbootBootstrap.checkProviderOpenFile(null, "invalid");
                else if (operation.equals("length")) GbootBootstrap.providerLength(null);
                else if (operation.equals("delete")) GbootBootstrap.denyProviderDelete();
                else throw new AssertionError(operation);
            });
        } else {
            throw new IllegalArgumentException(scenario);
        }
        System.out.println("PASS " + scenario + " (metadata/map only; no initialized Context/Uri runtime)");
    }
}
