#!/bin/bash

# Add imports for verticalScroll and rememberScrollState if missing
function add_imports {
    local file=$1
    if ! grep -q "import androidx.compose.foundation.verticalScroll" "$file"; then
        sed -i '' '/import androidx.compose.foundation.layout.Column/a\
import androidx.compose.foundation.verticalScroll\
import androidx.compose.foundation.rememberScrollState\
' "$file"
    fi
}

add_imports app/src/main/java/com/mewmix/nabu/screens/CreditsConstellationScreen.kt

# Replace main Column modifier to include vertical scroll
sed -i '' 's/modifier = Modifier.fillMaxSize(),/modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),/' app/src/main/java/com/mewmix/nabu/screens/CreditsConstellationScreen.kt

