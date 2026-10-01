package app.nook

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

// Brand fills and ordinary interactive text have different contrast requirements.
internal val NookActionBlue = Color(0xff3270e6)
internal val NookReadableBlue = Color(0xff7ea7f3)

@Composable internal fun NookButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(containerColor = NookActionBlue, contentColor = Color.White),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    Button(onClick, modifier, enabled, shape, colors, contentPadding = contentPadding, content = content)
}
