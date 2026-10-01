package app.nook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun CalendarAgendaRow(time: String, title: String, detail: String, open: (() -> Unit)? = null) {
    val font = FontFamily(Font(R.font.inter))
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).background(Color(0xff221d1a), RoundedCornerShape(18.dp))
        .then(if(open == null) Modifier else Modifier.clickable(role = Role.Button, onClick = open)).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(time, Modifier.width(70.dp), fontFamily = font, fontSize = 11.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold,
            color = if(time == "Anytime") Color(0xff3fae9b) else Color(0xffff7e1d))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontFamily = font, fontSize = 13.sp, lineHeight = 18.2.sp, fontWeight = FontWeight.Bold, color = Color(0xfff5eee7))
            Text(detail, fontFamily = font, fontSize = 10.sp, lineHeight = 18.sp, color = Color(0xffcbbfb4))
        }
    }
}
