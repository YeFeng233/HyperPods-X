policy=audio_policy_configuration.xml
mkdir -p "$MODPATH/system/vendor/etc"
awk '
    /<route type="mix" sink="BT A2DP (Out|Headphones)"/ { headphones = 1 }
    headphones && /sources=/ {
        if ($0 !~ /,spatial output"/) sub(/"\/>/, ",spatial output\"/>")
        headphones = 0
    }
    { print }
' "/vendor/etc/$policy" > "$MODPATH/system/vendor/etc/$policy"
