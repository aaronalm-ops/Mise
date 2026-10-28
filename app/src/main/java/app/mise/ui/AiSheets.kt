package app.mise.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.mise.data.Ai
import app.mise.data.Choices
import app.mise.data.Meal
import app.mise.data.PlanRepo
import app.mise.data.RecipeRepo
import app.mise.data.Suggestion
import app.mise.data.WeekIdeas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** The stamp breathing slowly while the AI works, with what it is doing. */
@Composable
fun Thinking(text: String, sub: String = "Usually 10 to 30 seconds") {
    val c = Mise.colours
    val t = rememberInfiniteTransition(label = "think")
    val a by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MiseStamp(64.dp, Modifier.graphicsLayer { alpha = a; scaleX = 0.96f + a * 0.04f; scaleY = 0.96f + a * 0.04f })
        Spacer(Modifier.height(16.dp))
        Text(text, style = Mise.type.bodyM, color = c.text, textAlign = TextAlign.Center)
        Text(sub.uppercase(), style = Mise.type.labelS, color = c.text2, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Shown when the AI helpers need the Mise account first. */
@Composable
private fun NeedsAccount(onHousehold: () -> Unit) {
    EmptyState("Sign in first", "The AI helpers run on your Mise account, so only you and Janice can use them. Sign in under Settings, Household.")
    Spacer(Modifier.height(12.dp))
    PrimaryButton("Open Household", onHousehold, Modifier.fillMaxWidth())
}

enum class AiRecipeMode { Photo, Idea }

/**
 * Recipe from a photo (up to four: a cookbook page, a card, a screenshot) or from an idea. The
 * result opens in the editor as a draft, marked To check, so nothing is saved without a look.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiRecipeSheet(mode: AiRecipeMode, onDismiss: () -> Unit, onDraft: (Long) -> Unit, onHousehold: () -> Unit) {
    val c = Mise.colours
    val ctx = LocalContext.current
    val repo = RecipeRepo.get(ctx)
    val scope = rememberCoroutineScope()
    var photos by remember { mutableStateOf(listOf<Uri>()) }
    var thumbs by remember { mutableStateOf(mapOf<Uri, ImageBitmap>()) }
    var hint by remember { mutableStateOf("") }
    var idea by remember { mutableStateOf("") }
    var serves by remember { mutableIntStateOf(4) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var pendingCamera by remember { mutableStateOf<Uri?>(null) }
    val confirm = Haptic.rememberConfirm()

    fun addPhotos(list: List<Uri>) {
        val next = (photos + list).distinct().take(4)
        photos = next
        scope.launch {
            val made = withContext(Dispatchers.IO) {
                next.filter { it !in thumbs }.mapNotNull { u ->
                    runCatching {
                        val src = android.graphics.ImageDecoder.createSource(ctx.contentResolver, u)
                        u to android.graphics.ImageDecoder.decodeBitmap(src) { d, info, _ ->
                            val s = 240.0 / maxOf(info.size.width, info.size.height)
                            if (s < 1) d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
                        }.asImageBitmap()
                    }.getOrNull()
                }
            }
            thumbs = thumbs + made
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris -> if (uris.isNotEmpty()) addPhotos(uris) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pendingCamera
        if (ok && u != null) addPhotos(listOf(u))
        pendingCamera = null
    }
    fun takePhoto() {
        val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
        val f = File(dir, "recipe-${System.currentTimeMillis()}.jpg")
        val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        pendingCamera = u
        camera.launch(u)
    }

    fun run() {
        error = null; busy = true
        job = scope.launch {
            try {
                val state = if (mode == AiRecipeMode.Photo) Ai.recipeFromPhotos(ctx, photos, hint) else Ai.recipeFromIdea(idea, serves)
                val id = repo.saveDraft(null, state)
                confirm()
                onDraft(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = Ai.friendly(e)
            } finally {
                busy = false
            }
        }
    }

    MiseSheet(
        title = if (mode == AiRecipeMode.Photo) "Recipe from a photo" else "Recipe from an idea",
        eyebrow = "Mise AI",
        onDismiss = { job?.cancel(); onDismiss() },
    ) {
        Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
            when {
                !Ai.available -> NeedsAccount(onHousehold)
                busy -> {
                    Thinking(if (mode == AiRecipeMode.Photo) "Reading the recipe" else "Writing your recipe")
                    GhostButton("Cancel", { job?.cancel(); busy = false }, Modifier.fillMaxWidth())
                }
                mode == AiRecipeMode.Photo -> {
                    Text(
                        "A cookbook page, a handwritten card or a screenshot. Add up to four photos if the recipe runs over pages.",
                        style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
                    )
                    if (photos.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            photos.forEach { u ->
                                Box(Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)).border(BorderStroke(1.dp, c.line), RoundedCornerShape(12.dp))) {
                                    thumbs[u]?.let { Image(it, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.size(88.dp)) }
                                    Box(
                                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(CircleShape)
                                            .background(c.surface)
                                            .clickable(role = Role.Button, onClickLabel = "Remove photo") { photos = photos - u },
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(MiseIcons.Close, contentDescription = "Remove photo", tint = c.text, modifier = Modifier.size(14.dp)) }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Take a photo", { takePhoto() }, Modifier.weight(1f), enabled = photos.size < 4)
                        GhostButton("From gallery", { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.weight(1f), enabled = photos.size < 4)
                    }
                    Spacer(Modifier.height(10.dp))
                    MiseField(hint, { hint = it.take(300) }, "Anything to know? e.g. Mum's recipe, double it", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(if (photos.isEmpty()) "Add a photo to start" else "Read the recipe", { run() }, Modifier.fillMaxWidth(), enabled = photos.isNotEmpty())
                }
                else -> {
                    MiseField(idea, { idea = it.take(600) }, "e.g. A quick prawn curry with coconut", Modifier.fillMaxWidth(), singleLine = false, minHeight = 88)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Under 30 minutes", "Vegetarian", "Use up spinach", "Air fryer", "Goan style", "Lighter").forEach { s ->
                            MiseChip(s, idea.contains(s, true)) {
                                idea = if (idea.contains(s, true)) idea.replace(Regex(",?\\s*" + Regex.escape(s), RegexOption.IGNORE_CASE), "").trim().trimStart(',').trim()
                                else listOf(idea.trim(), s.lowercase()).filter { it.isNotEmpty() }.joinToString(", ")
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Servings", style = Mise.type.bodyM, color = c.text, modifier = Modifier.weight(1f))
                        Stepper(serves, { serves = it }, label = "servings")
                    }
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(if (idea.isBlank()) "Say what you would like" else "Write the recipe", { run() }, Modifier.fillMaxWidth(), enabled = idea.isNotBlank())
                }
            }
            error?.let { Text(it, style = Mise.type.bodyS, color = c.danger, modifier = Modifier.padding(start = 4.dp, top = 12.dp)) }
            if (!busy && Ai.available) {
                Text("The result opens as a draft marked To check. Nothing is saved until you tap Save.", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 12.dp))
            }
        }
    }
}

/**
 * Suggest the week: Mise AI picks dishes from your own recipes for the empty slots, favouring
 * ones you have not had for a while and keeping the week varied. Untick any, then add the rest.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestWeekSheet(weekStart: LocalDate, onDismiss: () -> Unit, onHousehold: () -> Unit) {
    val c = Mise.colours
    val ctx = LocalContext.current
    val recipes = RecipeRepo.get(ctx)
    val plan = PlanRepo.get(ctx)
    val scope = rememberCoroutineScope()
    val library by remember { recipes.summaries() }.collectAsState(initial = null)
    val entries by remember(weekStart) { plan.week(weekStart) }.collectAsState(initial = emptyList())
    val notes by remember(weekStart) { plan.notes(weekStart) }.collectAsState(initial = emptyList())
    var meals by remember { mutableStateOf(setOf(Meal.DINNER)) }
    var wishes by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var ideas by remember { mutableStateOf<WeekIdeas?>(null) }
    var off by remember { mutableStateOf(setOf<Int>()) }
    var job by remember { mutableStateOf<Job?>(null) }
    val confirm = Haptic.rememberConfirm()

    val today = LocalDate.now()
    val days = (0..6).map { weekStart.plusDays(it.toLong()) }.filter { !it.isBefore(today) }
    val slots = days.associateWith { d -> meals.filter { m -> entries.none { it.date == d && it.meal == m } && notes.none { it.date == d && it.meal == m } } }
        .filterValues { it.isNotEmpty() }
    val slotCount = slots.values.sumOf { it.size }

    fun ask() {
        val lib = library ?: return
        error = null; busy = true; ideas = null; off = emptySet()
        job = scope.launch {
            try {
                ideas = Ai.suggestWeek(lib, entries, notes, slots.mapValues { it.value.toList() }, wishes)
                if (ideas?.items.isNullOrEmpty()) error = "No suggestions came back. Try again, or loosen your wishes."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = Ai.friendly(e)
            } finally {
                busy = false
            }
        }
    }

    MiseSheet(title = "Suggest dishes", eyebrow = "Mise AI · " + weekTitle(weekStart), onDismiss = { job?.cancel(); onDismiss() }) {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            val lib = library
            val result = ideas
            when {
                !Ai.available -> NeedsAccount(onHousehold)
                lib != null && lib.size < 5 -> EmptyState("Add a few more recipes first", "Suggestions come from your own recipes, so there need to be at least five.")
                busy -> {
                    Thinking("Looking through your ${lib?.size ?: 0} recipes")
                    GhostButton("Cancel", { job?.cancel(); busy = false }, Modifier.fillMaxWidth())
                }
                result != null && result.items.isNotEmpty() -> {
                    if (result.summary.isNotBlank()) Text(result.summary, style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                    Card {
                        result.items.forEachIndexed { i, s ->
                            if (i > 0) Divider()
                            SuggestionRow(s, checked = i !in off) { off = if (i in off) off - i else off + i }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val n = result.items.size - off.size
                    PrimaryButton(if (n == 0) "Tick the dishes to add" else if (n == 1) "Add 1 dish to the plan" else "Add $n dishes to the plan", {
                        val chosen = result.items.filterIndexed { i, _ -> i !in off }
                        plan.scope.launch {
                            val ids = chosen.mapNotNull { s ->
                                val r = recipes.get(s.recipe.id) ?: return@mapNotNull null
                                plan.addEntry(s.date, s.meal, r.id, plan.defaultServings(r), Choices.decode("", r))
                            }
                            Toasts.undo(if (ids.size == 1) "Added 1 dish" else "Added ${ids.size} dishes") {
                                plan.scope.launch { ids.forEach { plan.removeEntry(it) } }
                            }
                        }
                        confirm()
                        onDismiss()
                    }, Modifier.fillMaxWidth(), enabled = n > 0)
                    Spacer(Modifier.height(8.dp))
                    GhostButton("Suggest again", { ask() }, Modifier.fillMaxWidth())
                }
                else -> {
                    Text("Which meals?", style = Mise.type.bodyM, color = c.text, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Meal.entries.forEach { m ->
                            MiseChip(m.label, m in meals) { meals = if (m in meals) meals - m else meals + m }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    MiseField(wishes, { wishes = it.take(400) }, "Wishes, e.g. fish once, lighter on weekdays", Modifier.fillMaxWidth(), imeAction = ImeAction.Done)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (slotCount == 0) "Every chosen meal from today on is already planned." else "$slotCount empty " + (if (slotCount == 1) "slot" else "slots") + " from today on. Only your own recipes are used.",
                        style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
                    )
                    PrimaryButton(if (slotCount == 0) "Nothing left to fill" else "Suggest dishes", { ask() }, Modifier.fillMaxWidth(), enabled = slotCount > 0 && lib != null)
                }
            }
            error?.let { Text(it, style = Mise.type.bodyS, color = c.danger, modifier = Modifier.padding(start = 4.dp, top = 12.dp)) }
        }
    }
}

@Composable
private fun SuggestionRow(s: Suggestion, checked: Boolean, onToggle: () -> Unit) {
    val c = Mise.colours
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Checkbox, onClick = onToggle).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).border(BorderStroke(1.5.dp, if (checked) c.control else c.text2), CircleShape)
                .then(if (checked) Modifier.background(c.control, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(MiseIcons.Check, contentDescription = null, tint = c.onControl, modifier = Modifier.size(14.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(slotLabel(s.date, s.meal).uppercase(), style = Mise.type.labelS, color = c.text2)
            Text(s.recipe.title, style = Mise.type.bodyM, color = if (checked) c.text else c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.reason.isNotBlank()) Text(s.reason, style = Mise.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
