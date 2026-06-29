.class public final Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;
.super Ljava/lang/Object;
.source "SettingsBackgroundTransparencyUtils.java"


# direct methods
.method public constructor <init>()V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method private static applyColoredPadding(Landroid/view/View;I)V
    .locals 1

    instance-of v0, p0, Lcom/samsung/android/settings/core/SecColoredPaddingView;

    if-eqz v0, :cond_0

    move-object v0, p0

    check-cast v0, Lcom/samsung/android/settings/core/SecColoredPaddingView;

    iput p1, v0, Lcom/samsung/android/settings/core/SecColoredPaddingView;->paddingColor:I

    invoke-virtual {p0}, Landroid/view/View;->invalidate()V

    :cond_0
    return-void
.end method

.method private static applyNamedRoot(Landroid/app/Activity;Ljava/lang/String;I)V
    .locals 3

    invoke-virtual {p0}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;

    move-result-object v0

    const-string v1, "id"

    invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;

    move-result-object v2

    invoke-virtual {v0, p1, v1, v2}, Landroid/content/res/Resources;->getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I

    move-result p1

    if-eqz p1, :cond_0

    invoke-virtual {p0, p1}, Landroid/app/Activity;->findViewById(I)Landroid/view/View;

    move-result-object p0

    invoke-static {p0, p2}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->applySolidRoot(Landroid/view/View;I)V

    :cond_0
    return-void
.end method

.method private static applySolidRoot(Landroid/view/View;I)V
    .locals 0

    if-eqz p0, :cond_0

    invoke-virtual {p0, p1}, Landroid/view/View;->setBackgroundColor(I)V

    invoke-static {p0, p1}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->applyColoredPadding(Landroid/view/View;I)V

    :cond_0
    return-void
.end method

.method private static applyToViewTree(Landroid/view/View;I)V
    .locals 3

    if-eqz p0, :cond_2

    invoke-static {p0, p1}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->applyColoredPadding(Landroid/view/View;I)V

    invoke-static {p0}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->shouldApplySurface(Landroid/view/View;)Z

    move-result v0

    if-eqz v0, :cond_0

    invoke-virtual {p0, p1}, Landroid/view/View;->setBackgroundColor(I)V

    :cond_0
    instance-of v0, p0, Landroid/view/ViewGroup;

    if-eqz v0, :cond_2

    check-cast p0, Landroid/view/ViewGroup;

    invoke-virtual {p0}, Landroid/view/ViewGroup;->getChildCount()I

    move-result v0

    const/4 v1, 0x0

    :goto_0
    if-ge v1, v0, :cond_2

    invoke-virtual {p0, v1}, Landroid/view/ViewGroup;->getChildAt(I)Landroid/view/View;

    move-result-object v2

    invoke-static {v2, p1}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->applyToViewTree(Landroid/view/View;I)V

    add-int/lit8 v1, v1, 0x1

    goto :goto_0

    :cond_2
    return-void
.end method

.method public static apply(Landroid/content/Context;)V
    .locals 4

    if-eqz p0, :cond_2

    instance-of v0, p0, Landroid/app/Activity;

    if-eqz v0, :cond_2

    invoke-static {p0}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->getBaseBackgroundColor(Landroid/content/Context;)I

    move-result v0

    invoke-static {p0}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->getTransparency(Landroid/content/Context;)I

    move-result v1

    mul-int/lit16 v1, v1, 0xff

    div-int/lit8 v1, v1, 0x64

    const/16 v2, 0xff

    sub-int v1, v2, v1

    invoke-static {v0, v1}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->withAlpha(II)I

    move-result v0

    check-cast p0, Landroid/app/Activity;

    invoke-virtual {p0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;

    move-result-object v1

    if-eqz v1, :cond_1

    ushr-int/lit8 v2, v0, 0x18

    const/16 v3, 0xff

    if-ne v2, v3, :cond_3

    const/4 v2, -0x1

    invoke-virtual {v1, v2}, Landroid/view/Window;->setFormat(I)V

    const v2, 0x100000

    invoke-virtual {v1, v2}, Landroid/view/Window;->clearFlags(I)V

    goto :goto_0

    :cond_3
    const/4 v2, -0x3

    invoke-virtual {v1, v2}, Landroid/view/Window;->setFormat(I)V

    const v2, 0x100000

    invoke-virtual {v1, v2}, Landroid/view/Window;->addFlags(I)V

    :goto_0

    invoke-virtual {v1, v0}, Landroid/view/Window;->setStatusBarColor(I)V

    invoke-virtual {v1, v0}, Landroid/view/Window;->setNavigationBarColor(I)V

    new-instance v2, Landroid/graphics/drawable/ColorDrawable;

    invoke-direct {v2, v0}, Landroid/graphics/drawable/ColorDrawable;-><init>(I)V

    invoke-virtual {v1, v2}, Landroid/view/Window;->setBackgroundDrawable(Landroid/graphics/drawable/Drawable;)V

    invoke-virtual {v1}, Landroid/view/Window;->getDecorView()Landroid/view/View;

    move-result-object v1

    invoke-static {v1, v0}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->applyToViewTree(Landroid/view/View;I)V

    :cond_1
    const v1, 0x1020002

    invoke-virtual {p0, v1}, Landroid/app/Activity;->findViewById(I)Landroid/view/View;

    move-result-object v1

    invoke-static {v1, v0}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->applySolidRoot(Landroid/view/View;I)V

    const-string v1, "included_window_inset"

    invoke-static {p0, v1, v0}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->applyNamedRoot(Landroid/app/Activity;Ljava/lang/String;I)V

    const-string v1, "content_parent"

    invoke-static {p0, v1, v0}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->applyNamedRoot(Landroid/app/Activity;Ljava/lang/String;I)V

    const-string v1, "coordinator"

    invoke-static {p0, v1, v0}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->applyNamedRoot(Landroid/app/Activity;Ljava/lang/String;I)V

    const-string v1, "content_layout"

    invoke-static {p0, v1, v0}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->applyNamedRoot(Landroid/app/Activity;Ljava/lang/String;I)V

    const-string v1, "content_frame"

    invoke-static {p0, v1, v0}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->applyNamedRoot(Landroid/app/Activity;Ljava/lang/String;I)V

    const-string v1, "main_content"

    invoke-static {p0, v1, v0}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->applyNamedRoot(Landroid/app/Activity;Ljava/lang/String;I)V

    const-string v1, "app_bar"

    invoke-static {p0, v1, v0}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->applyNamedRoot(Landroid/app/Activity;Ljava/lang/String;I)V

    const-string v1, "collapsing_app_bar"

    invoke-static {p0, v1, v0}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->applyNamedRoot(Landroid/app/Activity;Ljava/lang/String;I)V

    const-string v1, "round_corner"

    invoke-static {p0, v1, v0}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->applyNamedRoot(Landroid/app/Activity;Ljava/lang/String;I)V

    :cond_2
    return-void
.end method

.method public static clamp(I)I
    .locals 1

    if-ltz p0, :cond_0

    const/16 v0, 0x64

    if-le p0, v0, :cond_1

    move p0, v0

    goto :goto_0

    :cond_0
    const/4 p0, 0x0

    :cond_1
    :goto_0
    return p0
.end method

.method private static getBaseBackgroundColor(Landroid/content/Context;)I
    .locals 4

    if-eqz p0, :cond_0

    invoke-virtual {p0}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;

    move-result-object v0

    const-string v1, "sec_widget_round_and_bgcolor"

    const-string v2, "color"

    invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;

    move-result-object v3

    invoke-virtual {v0, v1, v2, v3}, Landroid/content/res/Resources;->getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I

    move-result v0

    if-eqz v0, :cond_1

    invoke-virtual {p0, v0}, Landroid/content/Context;->getColor(I)I

    move-result p0

    return p0

    :cond_0

    :cond_1
    invoke-static {p0}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->isDarkMode(Landroid/content/Context;)Z

    move-result p0

    if-eqz p0, :cond_2

    const/high16 p0, -0x1000000

    return p0

    :cond_2
    const/4 p0, -0x1

    return p0
.end method

.method public static getTransparency(Landroid/content/Context;)I
    .locals 2

    if-eqz p0, :cond_0

    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    const-string v0, "unica_settings_bg_transparency_level"

    const/4 v1, 0x0

    invoke-static {p0, v0, v1}, Landroid/provider/Settings$System;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I

    move-result p0

    invoke-static {p0}, Lio/mesalabs/unica/settings/display/SettingsBackgroundTransparencyUtils;->clamp(I)I

    move-result p0

    return p0

    :cond_0
    const/4 p0, 0x0

    return p0
.end method

.method private static shouldApplySurface(Landroid/view/View;)Z
    .locals 1

    instance-of v0, p0, Landroidx/recyclerview/widget/RecyclerView;

    if-nez v0, :cond_0

    instance-of v0, p0, Landroidx/core/widget/NestedScrollView;

    if-nez v0, :cond_0

    instance-of v0, p0, Landroid/widget/ListView;

    if-nez v0, :cond_0

    instance-of v0, p0, Landroid/widget/ScrollView;

    if-nez v0, :cond_0

    const/4 v0, 0x0

    return v0

    :cond_0
    const/4 v0, 0x1

    return v0
.end method

.method private static isDarkMode(Landroid/content/Context;)Z
    .locals 2

    if-eqz p0, :cond_0

    invoke-virtual {p0}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;

    move-result-object p0

    invoke-virtual {p0}, Landroid/content/res/Resources;->getConfiguration()Landroid/content/res/Configuration;

    move-result-object p0

    iget p0, p0, Landroid/content/res/Configuration;->uiMode:I

    and-int/lit8 p0, p0, 0x30

    const/16 v0, 0x20

    if-ne p0, v0, :cond_0

    const/4 p0, 0x1

    return p0

    :cond_0
    const/4 p0, 0x0

    return p0
.end method

.method private static withAlpha(II)I
    .locals 1

    if-ltz p1, :cond_0

    const/16 v0, 0xff

    if-le p1, v0, :cond_1

    move p1, v0

    goto :goto_0

    :cond_0
    const/4 p1, 0x0

    :cond_1
    :goto_0
    const v0, 0xffffff

    and-int/2addr p0, v0

    shl-int/lit8 p1, p1, 0x18

    or-int/2addr p0, p1

    return p0
.end method
