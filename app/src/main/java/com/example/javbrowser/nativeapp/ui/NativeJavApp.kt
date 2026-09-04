@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.javbrowser.nativeapp.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import coil.compose.AsyncImage
import com.example.javbrowser.R
import com.example.javbrowser.nativeapp.data.*
import com.example.javbrowser.nativeapp.domain.*
import com.example.javbrowser.nativeapp.web.SourceVerificationActivity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView

private val AppColors = darkColorScheme(
    background = Color(0xFF0C0C0F), surface = Color(0xFF151518), surfaceContainer = Color(0xFF1C1C21),
    primary = Color(0xFFC7B7FF), onPrimary = Color(0xFF291A52), secondary = Color(0xFFBFC2D9), error = Color(0xFFFFB4AB)
)

private enum class MainDestination(val route: String, val icon: @Composable () -> Unit, val label: Int) {
    HOME("home", { Icon(Icons.Default.Home, null) }, R.string.nav_home),
    DISCOVER("discover", { Icon(Icons.Default.Explore, null) }, R.string.nav_discover),
    LIBRARY("library", { Icon(Icons.Default.VideoLibrary, null) }, R.string.nav_library),
    DOWNLOADS("downloads", { Icon(Icons.Default.Download, null) }, R.string.nav_downloads),
}

@Composable
fun NativeJavApp(repository: JavRepository, library: LibraryStore, incoming: String?) {
    val vm: AppViewModel = viewModel(factory = AppViewModelFactory(repository, library))
    val state by vm.state.collectAsState()
    val nav = rememberNavController()
    MaterialTheme(colorScheme = AppColors, typography = Typography()) {
        LaunchedEffect(incoming) { incoming?.takeIf { it.isNotBlank() }?.let { vm.search(it); nav.navigate("search") } }
        Scaffold(
            bottomBar = { if (nav.currentRoute() in MainDestination.entries.map { it.route }) MainNavigation(nav) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                composable("home") { HomeScreen(state, onSearch = { vm.search(it); nav.navigate("search") }, onSettings = { nav.navigate("settings") }, onSelect = { vm.select(it); nav.navigate("detail") }) }
                composable("search") { SearchScreen(state, onBack = nav::popBackStack, onSearch = vm::search, onSelect = { vm.select(it); nav.navigate("detail") }) }
                composable("discover") { DiscoverScreen(onSearch = { vm.search(it); nav.navigate("search") }) }
                composable("library") { LibraryScreen(state.favorites, onSelect = { vm.select(it); nav.navigate("detail") }) }
                composable("downloads") { EmptyScreen(Icons.Default.Download, stringResource(R.string.downloads_empty), stringResource(R.string.downloads_empty_body)) }
                composable("settings") { SettingsScreen(repository, nav::popBackStack) }
                composable("detail") { DetailScreen(state, nav::popBackStack, vm::toggleFavorite, onPlay = { vm.play(it); nav.navigate("player") }) }
                composable("player") { state.activePlayback?.let { PlayerScreen(it, nav::popBackStack) } ?: EmptyScreen(Icons.Default.ErrorOutline, stringResource(R.string.no_playback), stringResource(R.string.try_another_source)) }
            }
        }
    }
}

@Composable private fun NavHostController.currentRoute(): String? {
    val entry by currentBackStackEntryAsState(); return entry?.destination?.route
}

@Composable private fun MainNavigation(nav: NavHostController) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        val route = nav.currentRoute()
        MainDestination.entries.forEach { dest -> NavigationBarItem(selected = route == dest.route, onClick = { nav.navigate(dest.route) { popUpTo("home"); launchSingleTop = true } }, icon = dest.icon, label = { Text(stringResource(dest.label)) }, modifier = Modifier.testTag("nav-${dest.route}")) }
    }
}

@Composable private fun HomeScreen(state: AppUiState, onSearch: (String)->Unit, onSettings:()->Unit, onSelect:(JavTitle)->Unit) {
    var query by remember { mutableStateOf("") }
    LazyColumn(Modifier.testTag("screen-home"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(stringResource(R.string.home_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant) }; IconButton(onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) } } }
        item { SearchSurface(query, { query=it }, { onSearch(query) }) }
        if (state.favorites.isNotEmpty()) item { MediaSection(stringResource(R.string.favorites), state.favorites, onSelect) }
        item { FeatureHero(onSearch) }
    }
}

@Composable private fun SearchSurface(value:String,onChange:(String)->Unit,onSubmit:()->Unit) {
    TextField(value, onChange, modifier=Modifier.fillMaxWidth().testTag("global-search-input"), singleLine=true, placeholder={Text(stringResource(R.string.search_hint))}, leadingIcon={Icon(Icons.Default.Search,null)}, trailingIcon={IconButton(onSubmit,Modifier.testTag("global-search-submit")){Icon(Icons.Default.ArrowForward,stringResource(R.string.search))}}, shape=RoundedCornerShape(20.dp), colors=TextFieldDefaults.colors(unfocusedIndicatorColor=Color.Transparent,focusedIndicatorColor=Color.Transparent))
}

@Composable private fun FeatureHero(onSearch:(String)->Unit) {
    Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF33265A),Color(0xFF191722)))).clickable { onSearch("latest") }.padding(24.dp)) {
        Column(Modifier.align(Alignment.BottomStart)) { Text(stringResource(R.string.discover_title),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold); Text(stringResource(R.string.discover_body),color=MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(12.dp)); AssistChip(onClick={onSearch("latest")},label={Text(stringResource(R.string.explore_now))},leadingIcon={Icon(Icons.Default.Explore,null)}) }
    }
}

@Composable private fun SearchScreen(state:AppUiState,onBack:()->Unit,onSearch:(String)->Unit,onSelect:(JavTitle)->Unit) {
    var query by remember(state.query){mutableStateOf(state.query)}
    Column(Modifier.testTag("screen-search")) { TopAppBar(title={Text(stringResource(R.string.search))},navigationIcon={IconButton(onBack){Icon(Icons.Default.ArrowBack,null)}}); Box(Modifier.padding(horizontal=16.dp)){SearchSurface(query,{query=it},{onSearch(query)})}; SourceStatus(state.search.sourceStates); when { state.search.results.isEmpty() && state.search.sourceStates.values.any{it is SourceLoadState.Loading} -> LoadingGrid(); state.search.results.isEmpty() && state.search.complete -> EmptyScreen(Icons.Default.SearchOff,stringResource(R.string.no_results),stringResource(R.string.no_results_body)); else -> MediaGrid(state.search.results,onSelect) } }
}

@Composable private fun SourceStatus(states:Map<String,SourceLoadState>) { if(states.isNotEmpty()) LazyRow(contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){ items(states.entries.toList()){(id,status)-> val text=when(status){SourceLoadState.Loading->"$id · …";is SourceLoadState.Success->"$id · ${status.count}";is SourceLoadState.Error->"$id · ${if(status.verificationRequired) stringResource(R.string.verify) else stringResource(R.string.unavailable)}"}; Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainer){Row(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){when(status){SourceLoadState.Loading->CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp);is SourceLoadState.Success->Icon(Icons.Default.Check,null,Modifier.size(16.dp));is SourceLoadState.Error->Icon(Icons.Default.Warning,null,Modifier.size(16.dp))};Spacer(Modifier.width(6.dp));Text(text)}} } } }

@Composable private fun MediaGrid(titles:List<JavTitle>,onSelect:(JavTitle)->Unit) { LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){items(titles,key={it.id}){MediaRow(it,onSelect)}} }
@Composable private fun MediaRow(title:JavTitle,onSelect:(JavTitle)->Unit){ Card(Modifier.fillMaxWidth().testTag("media-${title.id}").clickable{onSelect(title)},shape=RoundedCornerShape(16.dp)){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){Poster(title,90.dp,126.dp);Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(title.code ?: stringResource(R.string.unknown_code),color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold);Text(title.title,maxLines=2,overflow=TextOverflow.Ellipsis);Spacer(Modifier.height(8.dp));Text(pluralStringResource(R.plurals.sources_available,title.sourceRefs.size,title.sourceRefs.size),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}} }
@Composable private fun Poster(title:JavTitle,w:androidx.compose.ui.unit.Dp,h:androidx.compose.ui.unit.Dp){AsyncImage(title.coverUrl,contentDescription=title.title,contentScale=ContentScale.Crop,modifier=Modifier.size(w,h).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer))}
@Composable private fun LoadingGrid(){LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){items(5){Box(Modifier.fillMaxWidth().height(136.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer))}}}
@Composable private fun MediaSection(title:String,items:List<JavTitle>,onSelect:(JavTitle)->Unit){Column{Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(12.dp));LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)){items(items,key={it.id}){item->Column(Modifier.width(126.dp).clickable{onSelect(item)}){Poster(item,126.dp,176.dp);Spacer(Modifier.height(8.dp));Text(item.code?:item.title,maxLines=1,overflow=TextOverflow.Ellipsis)}}}}}

@Composable private fun DetailScreen(state:AppUiState,onBack:()->Unit,onFavorite:()->Boolean,onPlay:(PlaybackVariant)->Unit){val title=state.selected?:return;LazyColumn(Modifier.testTag("screen-detail")){item{Box(Modifier.fillMaxWidth().height(360.dp)){AsyncImage(title.coverUrl,title.title,Modifier.fillMaxSize(),contentScale=ContentScale.Crop);Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.1f),MaterialTheme.colorScheme.background))));IconButton(onBack,Modifier.padding(8.dp).align(Alignment.TopStart).background(Color.Black.copy(.45f),RoundedCornerShape(50))){Icon(Icons.Default.ArrowBack,null)}}};item{Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Text(title.code?:stringResource(R.string.unknown_code),color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold);Text(title.title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Button(onClick={state.playback.firstOrNull()?.let(onPlay)},enabled=state.playback.isNotEmpty()){Icon(Icons.Default.PlayArrow,null);Spacer(Modifier.width(6.dp));Text(if(state.playbackLoading)stringResource(R.string.resolving)else stringResource(R.string.play))};FilledTonalButton(onClick={onFavorite()}){Icon(if(state.favorites.any{it.id==title.id})Icons.Default.Favorite else Icons.Default.FavoriteBorder,null);Spacer(Modifier.width(6.dp));Text(stringResource(R.string.favorite))}};if(state.detailsLoading)LinearProgressIndicator(Modifier.fillMaxWidth());Metadata(title);if(state.playback.isNotEmpty()){Text(stringResource(R.string.playback_sources),style=MaterialTheme.typography.titleMedium);state.playback.forEach{v->ListItem(headlineContent={Text("${v.sourceId.uppercase()} · ${v.label}")},supportingContent={Text(v.type.name)},leadingContent={Icon(Icons.Default.PlayCircle,null)},modifier=Modifier.clip(RoundedCornerShape(12.dp)).clickable{onPlay(v)})}};state.playbackErrors.forEach{(source,error)->Text("$source · $error",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}}}}}
@Composable private fun Metadata(t:JavTitle){val values=listOfNotNull(t.releaseDate?.toString(),t.durationMinutes?.let{"$it min"},t.maker?.name,t.rating?.let{"★ $it"});if(values.isNotEmpty())Text(values.joinToString(" · "),color=MaterialTheme.colorScheme.onSurfaceVariant);if(t.actors.isNotEmpty())EntityRow(t.actors);if(t.genres.isNotEmpty())EntityRow(t.genres)}
@Composable private fun EntityRow(values:List<JavEntity>){LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(values){entity->Surface(shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.surfaceContainer){Text(entity.name,Modifier.padding(horizontal=12.dp,vertical=8.dp))}}}}

@Composable private fun LibraryScreen(items:List<JavTitle>,onSelect:(JavTitle)->Unit){Column(Modifier.testTag("screen-library")){TopAppBar(title={Text(stringResource(R.string.library))});if(items.isEmpty())EmptyScreen(Icons.Default.FavoriteBorder,stringResource(R.string.library_empty),stringResource(R.string.library_empty_body))else MediaGrid(items,onSelect)}}
@Composable private fun DiscoverScreen(onSearch:(String)->Unit){val chips=listOf(R.string.latest,R.string.trending,R.string.popular,R.string.high_rated,R.string.actors,R.string.studios,R.string.series).map{stringResource(it)};LazyColumn(Modifier.testTag("screen-discover"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){item{Text(stringResource(R.string.discover_title),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)};items(chips){value->Card(Modifier.fillMaxWidth().clickable{onSearch(value)},shape=RoundedCornerShape(18.dp)){ListItem(headlineContent={Text(value)},leadingContent={Icon(Icons.Default.AutoAwesome,null)},trailingContent={Icon(Icons.Default.ChevronRight,null)})}}}}

@Composable private fun SettingsScreen(repository:JavRepository,onBack:()->Unit){val context=LocalContext.current;val prefs=context.getSharedPreferences("native_settings",Context.MODE_PRIVATE);var secure by remember{mutableStateOf(prefs.getBoolean("secure_screen",true))};LazyColumn(Modifier.testTag("screen-settings")){item{TopAppBar(title={Text(stringResource(R.string.settings))},navigationIcon={IconButton(onBack){Icon(Icons.Default.ArrowBack,null)}})};item{SettingsHeader(stringResource(R.string.settings_general))};item{ListItem(headlineContent={Text(stringResource(R.string.dark_theme))},supportingContent={Text(stringResource(R.string.dark_theme_body))},leadingContent={Icon(Icons.Default.DarkMode,null)})};item{SettingsHeader(stringResource(R.string.settings_sources))};items(repository.sourceSettings()){(h,verifyUrl)->ListItem(headlineContent={Text(h.sourceId.uppercase())},supportingContent={Text(h.lastError?:stringResource(R.string.source_ready))},leadingContent={Icon(if(h.recentFailures==0)Icons.Default.CheckCircle else Icons.Default.Warning,null)},trailingContent={verifyUrl?.let{url->TextButton(onClick={context.startActivity(Intent(context,SourceVerificationActivity::class.java).putExtra(SourceVerificationActivity.EXTRA_URL,url))}){Text(stringResource(R.string.verify))}}})};item{SettingsHeader(stringResource(R.string.settings_privacy))};item{ListItem(headlineContent={Text(stringResource(R.string.secure_screen))},supportingContent={Text(stringResource(R.string.secure_screen_body))},leadingContent={Icon(Icons.Default.VisibilityOff,null)},trailingContent={Switch(secure,{secure=it;prefs.edit().putBoolean("secure_screen",it).apply()})})};item{SettingsHeader(stringResource(R.string.settings_about))};item{ListItem(headlineContent={Text(stringResource(R.string.app_name))},supportingContent={Text(stringResource(R.string.about_body))},leadingContent={Icon(Icons.Default.Info,null)})}}}
@Composable private fun SettingsHeader(text:String){Text(text,Modifier.padding(start=20.dp,top=20.dp,bottom=4.dp),color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PlayerScreen(variant: PlaybackVariant, onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember(variant.url) {
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(variant.headers)
        ExoPlayer.Builder(context).build().apply {
            val item = MediaItem.fromUri(variant.url)
            val source = if (variant.type == StreamType.HLS) {
                HlsMediaSource.Factory(dataSource).createMediaSource(item)
            } else {
                ProgressiveMediaSource.Factory(dataSource).createMediaSource(item)
            }
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = true } },
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(12.dp)
                .align(Alignment.TopStart)
                .background(Color.Black.copy(.5f), RoundedCornerShape(50)),
        ) {
            Icon(Icons.Default.Close, null, tint = Color.White)
        }
    }
}
@Composable private fun EmptyScreen(icon:androidx.compose.ui.graphics.vector.ImageVector,title:String,body:String){Box(Modifier.fillMaxSize().padding(32.dp),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(icon,null,Modifier.size(52.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(12.dp));Text(title,style=MaterialTheme.typography.titleLarge);Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
