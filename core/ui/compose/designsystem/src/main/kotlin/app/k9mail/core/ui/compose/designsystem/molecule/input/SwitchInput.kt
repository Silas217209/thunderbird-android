package app.k9mail.core.ui.compose.designsystem.molecule.input

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.k9mail.core.ui.compose.designsystem.atom.Switch
import app.k9mail.core.ui.compose.designsystem.atom.text.TextBody1
import app.k9mail.core.ui.compose.theme.material2.MainTheme
import app.k9mail.core.ui.compose.theme.material2.PreviewWithThemes

@Composable
fun SwitchInput(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    contentPadding: PaddingValues = inputContentPadding(),
) {
    InputLayout(
        modifier = modifier,
        contentPadding = contentPadding,
        errorMessage = errorMessage,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MainTheme.spacings.half),
        ) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
            TextBody1(text = text)
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun SwitchInputPreview() {
    PreviewWithThemes {
        SwitchInput(
            text = "SwitchInput",
            checked = false,
            onCheckedChange = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun SwitchInputWithErrorPreview() {
    PreviewWithThemes {
        SwitchInput(
            text = "SwitchInput",
            checked = false,
            onCheckedChange = {},
            errorMessage = "Error message",
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun SwitchInputCheckedPreview() {
    PreviewWithThemes {
        SwitchInput(
            text = "SwitchInput",
            checked = true,
            onCheckedChange = {},
        )
    }
}
