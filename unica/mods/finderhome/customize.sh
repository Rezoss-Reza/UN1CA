SMALI_PATCH "system" "system/priv-app/TouchWizHome_2017/TouchWizHome_2017.apk" \
    "smali_classes2/com/honeyspace/common/Rune.smali" "replace" \
    'access$getSUPPORT_SEARCH_IN_INDICATOR$cp()Z' \
    'sget-boolean v0, Lcom/honeyspace/common/Rune;->SUPPORT_SEARCH_IN_INDICATOR:Z' \
    'const/4 v0, 0x1'
SMALI_PATCH "system" "system/priv-app/TouchWizHome_2017/TouchWizHome_2017.apk" \
    "smali_classes2/com/honeyspace/common/Rune.smali" "replace" \
    'access$getSUPPORT_SEMANTIC_SEARCH_ON_FINDER$cp()Z' \
    'sget-boolean v0, Lcom/honeyspace/common/Rune;->SUPPORT_SEMANTIC_SEARCH_ON_FINDER:Z' \
    'const/4 v0, 0x1'
SMALI_PATCH "system" "system/priv-app/TouchWizHome_2017/TouchWizHome_2017.apk" \
    "smali_classes2/com/honeyspace/common/Rune.smali" "replace" \
    'access$getSUPPORT_RECALL_ON_FINDER$cp()Z' \
    'sget-boolean v0, Lcom/honeyspace/common/Rune;->SUPPORT_RECALL_ON_FINDER:Z' \
    'const/4 v0, 0x1'
SMALI_PATCH "system" "system/priv-app/TouchWizHome_2017/TouchWizHome_2017.apk" \
    "smali_classes2/com/honeyspace/common/Rune.smali" "replace" \
    'access$getSUPPORT_GOOGLE_LINK_ON_FINDER$cp()Z' \
    'sget-boolean v0, Lcom/honeyspace/common/Rune;->SUPPORT_GOOGLE_LINK_ON_FINDER:Z' \
    'const/4 v0, 0x1'
