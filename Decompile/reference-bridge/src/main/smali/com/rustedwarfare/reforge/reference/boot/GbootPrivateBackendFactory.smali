.class public final Lcom/rustedwarfare/reforge/reference/boot/GbootPrivateBackendFactory;
.super Ljava/lang/Object;

.method public static createVerified()Lcom/corrodinggames/rts/gameFramework/e/c;
    .locals 3
    invoke-static {}, Lcom/rustedwarfare/reforge/reference/boot/GbootBootstrap;->requireRootsReady()V
    invoke-static {}, Lcom/rustedwarfare/reforge/reference/boot/GbootBootstrap;->privateRootWithSlash()Ljava/lang/String;
    move-result-object v1
    const-string v2, "reference-private"
    new-instance v0, Lcom/corrodinggames/rts/gameFramework/e/d;
    invoke-direct {v0, v1, v2}, Lcom/corrodinggames/rts/gameFramework/e/d;-><init>(Ljava/lang/String;Ljava/lang/String;)V
    return-object v0
.end method
