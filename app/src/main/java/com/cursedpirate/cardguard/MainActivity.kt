package com.cursedpirate.cardguard

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderWorker.schedule(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Home() } } }
    }
}

private fun riskColor(pct: Float) = when {
    pct >= 0.75f -> Color(0xFFE53935)
    pct >= 0.30f -> Color(0xFFFFB300)
    else -> Color(0xFF43A047)
}

@Composable
fun Home() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val cards by dao.cards().collectAsState(emptyList())
    val txns by dao.recentTxns().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<CardEntity?>(null) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.READ_SMS] == true) scope.launch {
            val n = importInbox(ctx)
            Toast.makeText(ctx, "Imported $n transactions", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("CardGuard", style = MaterialTheme.typography.headlineMedium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editing = CardEntity(bank = "", name = "", last4 = "", limit = 0.0) }) { Text("Add card") }
                OutlinedButton(onClick = {
                    perms.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS))
                }) { Text("Scan SMS") }
            }
        }
        items(cards, key = { "c${it.id}" }) { c ->
            val pct = if (c.limit > 0) (c.outstanding / c.limit).toFloat().coerceIn(0f, 1f) else 0f
            Card(onClick = { editing = c }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${c.bank} ${c.name} •••• ${c.last4}", style = MaterialTheme.typography.titleMedium)
                    Text("₹${"%,.0f".format(c.outstanding)} of ₹${"%,.0f".format(c.limit)}  (${(pct * 100).toInt()}%)")
                    LinearProgressIndicator(progress = { pct }, color = riskColor(pct), modifier = Modifier.fillMaxWidth())
                    if (c.dueDay > 0) Text("Due day ${c.dueDay}, in ${daysUntilDue(java.time.LocalDate.now(), c.dueDay)} days")
                }
            }
        }
        item { Text("Recent transactions", style = MaterialTheme.typography.titleMedium) }
        items(txns, key = { "t${it.id}" }) { t ->
            val sign = if (t.type == TxType.DEBIT) "-" else "+"
            Text("$sign₹${"%,.0f".format(t.amount)}  ${t.merchant ?: if (t.type == TxType.CREDIT) "Payment/refund" else ""}")
        }
    }

    editing?.let { c -> CardDialog(c, onDismiss = { editing = null },
        onSave = { scope.launch { dao.upsert(it) }; editing = null },
        onDelete = { scope.launch { dao.deleteTxns(it.id); dao.delete(it) }; editing = null }) }
}

@Composable
fun CardDialog(c: CardEntity, onDismiss: () -> Unit, onSave: (CardEntity) -> Unit, onDelete: (CardEntity) -> Unit) {
    var bank by remember { mutableStateOf(c.bank) }
    var name by remember { mutableStateOf(c.name) }
    var last4 by remember { mutableStateOf(c.last4) }
    var limit by remember { mutableStateOf(if (c.limit > 0) c.limit.toLong().toString() else "") }
    var due by remember { mutableStateOf(if (c.dueDay > 0) c.dueDay.toString() else "") }
    var out by remember { mutableStateOf(if (c.outstanding > 0) c.outstanding.toLong().toString() else "") }
    val valid = bank.isNotBlank() && last4.length == 4 && (due.toIntOrNull() ?: 0) in 0..31
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (c.id == 0L) "Add card" else "Edit card") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(bank, { bank = it }, label = { Text("Bank") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Card name") }, singleLine = true)
                OutlinedTextField(last4, { last4 = it.filter(Char::isDigit).take(4) }, label = { Text("Last 4 digits") }, singleLine = true)
                OutlinedTextField(limit, { limit = it.filter(Char::isDigit) }, label = { Text("Limit ₹") }, singleLine = true)
                OutlinedTextField(out, { out = it.filter(Char::isDigit) }, label = { Text("Outstanding ₹") }, singleLine = true)
                OutlinedTextField(due, { due = it.filter(Char::isDigit).take(2) }, label = { Text("Due day of month") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(c.copy(bank = bank.trim(), name = name.trim(), last4 = last4, limit = limit.toDoubleOrNull() ?: 0.0,
                    outstanding = out.toDoubleOrNull() ?: 0.0, dueDay = due.toIntOrNull() ?: 0))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (c.id != 0L) TextButton(onClick = { onDelete(c) }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
