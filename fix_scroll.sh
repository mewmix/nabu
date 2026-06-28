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

add_imports app/src/main/java/com/mewmix/nabu/screens/InitScreen.kt
sed -i '' 's/Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {/Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {/' app/src/main/java/com/mewmix/nabu/screens/InitScreen.kt

add_imports app/src/main/java/com/mewmix/nabu/screens/OptionalPermissionsScreen.kt
sed -i '' 's/Column(/Column(modifier = Modifier.verticalScroll(rememberScrollState()), /' app/src/main/java/com/mewmix/nabu/screens/OptionalPermissionsScreen.kt

add_imports app/src/main/java/com/mewmix/nabu/screens/MoreScreen.kt
sed -i '' '0,/Column(/s//Column(modifier = Modifier.verticalScroll(rememberScrollState()), /' app/src/main/java/com/mewmix/nabu/screens/MoreScreen.kt

