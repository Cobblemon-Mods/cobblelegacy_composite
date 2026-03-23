# Composite - Analyse d'Optimisation

## Resume des problemes identifies

| # | Probleme | Severite | Impact FPS | Impact Memoire |
|---|---|---|---|---|
| 1 | Double blit du framebuffer a chaque frame | CRITIQUE | Eleve | Faible |
| 2 | Pas de nettoyage GPU a la fermeture d'ecran | HAUTE | Nul | Eleve |
| 3 | Vertex buffer recree a chaque frame (Texture/Item) | HAUTE | Moyen | Moyen |
| 4 | Recreation complete de la surface au resize | MOYENNE | Pic ponctuel | Moyen |
| 5 | ComposeHud appelle init() a chaque frame | MOYENNE | Faible | Faible |
| 6 | Tooltip item rendu a chaque frame au survol | BASSE | Faible | Faible |
| 7 | Pas de frame skipping | BASSE | Faible | Nul |
| 8 | Taille du JAR (~116 Mo) | INFO | Nul | Nul |

---

## 1. CRITIQUE - Double blit du framebuffer

### Ou
`SkiaSurface.kt` - methode `render()` (lignes 75-95)

### Le probleme
A chaque frame, le rendu fait :
1. Copie le framebuffer principal de Minecraft vers le framebuffer Composite (blit 1)
2. Skia dessine par-dessus
3. Copie le framebuffer Composite vers le framebuffer Minecraft (blit 2)

Ca fait **2 copies plein ecran de pixels GPU** a chaque frame. Sur un ecran 1440p ou 4K, c'est enorme.

### Impact
- **GPU bandwidth** : 2 blits = 2x (largeur x hauteur x 4 octets) transferes par frame
- A 1440p 60fps : ~1.5 Go/s de bande passante GPU gaspillee
- A 4K 60fps : ~4 Go/s de bande passante GPU gaspillee

### Solution recommandee
Rendre Skia directement sur le framebuffer principal de Minecraft au lieu de passer par un framebuffer intermediaire. Le `BackendRenderTarget` peut pointer directement vers le FBO de Minecraft :
- Recuperer l'ID du framebuffer actif via `GL30.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)`
- Creer le BackendRenderTarget dessus
- Plus besoin de blit du tout

### Gain estime
- **Elimination de ~70-80% du cout GPU** du rendu Composite
- Reduction significative de la latence de rendu

---

## 2. HAUTE - Pas de nettoyage GPU a la fermeture

### Ou
`ComposeGui.kt` - methode `onClose()` (lignes 131-134)
`SkiaSurface.kt` - aucune methode `close()` ou `dispose()`

### Le probleme
Quand un ecran Compose est ferme :
- La `CanvasLayersComposeScene` est fermee (bien)
- Le callback GLFW est restaure (bien)
- **MAIS** : le framebuffer OpenGL, la texture, le BackendRenderTarget et la Surface Skia ne sont **jamais liberes**

A chaque ouverture/fermeture du Pokematos (ou autre ecran), des ressources GPU s'accumulent et ne sont jamais liberees.

### Impact
- **Fuite memoire GPU** : chaque ouverture/fermeture d'ecran perd ~quelques Mo de VRAM
- Sur une session longue (heures de jeu), ca peut s'accumuler
- Peut causer des ralentissements progressifs ou des crashs VRAM sur les petites configs

### Solution recommandee
Ajouter une methode `close()` a `SkiaSurface` et l'appeler dans `ComposeGui.onClose()` :
- Fermer `surface` (Skia Surface)
- Fermer `target` (BackendRenderTarget)
- Supprimer le framebuffer GL (`glDeleteFramebuffers`)
- La `TextureTarget` se gere via Minecraft

### Gain estime
- **Zero fuite memoire GPU** sur les sessions longues
- Stabilite amelioree

---

## 3. HAUTE - Vertex buffer recree a chaque frame

### Ou
`Texture.kt` - dans le `LaunchedEffect` (lignes 50-100)
`Item.kt` - dans le `LaunchedEffect` (lignes 40-80)

### Le probleme
Pour les composants `Texture()` et `Item()`, le rendu Minecraft (vertex buffer, setup shader, scissor test) est recree **a chaque frame** via `record()`, meme si rien n'a change (meme position, meme taille, meme texture).

### Impact
- **CPU** : allocation et construction du vertex buffer chaque frame
- **GPU** : upload du buffer a chaque frame
- **GC Pressure** : les anciens buffers sont garbage collectes

### Solution recommandee
Cacher le rendu et ne le recalculer que quand quelque chose change :
- Stocker la position/taille precedente
- Ne re-enregistrer le call que si les coordonnees ou la texture ont change
- Pour les items : ne re-rendre que si l'ItemStack ou la position change

### Gain estime
- **Reduction de 80-90% des appels de rendu** quand l'UI est statique
- Moins de pression sur le garbage collector

---

## 4. MOYENNE - Recreation complete de la surface au resize

### Ou
`SkiaSurface.kt` - methode `resize()` (lignes 31-67)

### Le probleme
Au resize de la fenetre Minecraft :
1. L'ancienne Surface Skia est fermee
2. L'ancien BackendRenderTarget est ferme
3. La TextureTarget est redimensionnee
4. Un nouveau BackendRenderTarget est cree
5. Une nouvelle Surface est creee
6. Le framebuffer est re-attache

C'est un processus lourd qui cause un **frame drop visible** pendant le resize.

### Impact
- **Freeze momentane** lors du resize (50-100ms)
- Pas d'impact en gameplay normal (le resize est rare)

### Solution recommandee
- Pre-allouer a une taille maximale et n'utiliser qu'une sous-region
- Ou utiliser un pool de surfaces pour eviter la re-creation

### Gain estime
- Faible en pratique (resize rare), mais meilleure experience utilisateur

---

## 5. MOYENNE - ComposeHud appelle init() a chaque frame

### Ou
`ComposeHud.kt` - methode `onHudRender()` (ligne 11)

### Le probleme
`gui.init()` est appele a **chaque frame** dans le callback HUD. Meme si `init()` contient une verification de taille pour eviter le travail inutile, ca reste un appel de methode avec des comparaisons a chaque frame.

### Impact
- Faible mais inutile
- Appels de methode et comparaisons chaque frame pour rien

### Solution recommandee
Ajouter un flag `initialized` dans `ComposeGui` :
```
private var initialized = false

fun init() {
    if (initialized && /* taille inchangee */) return
    initialized = true
    // ... init
}
```

### Gain estime
- Negligeable mais proprete du code

---

## 6. BASSE - Tooltip item rendu a chaque frame

### Ou
`Item.kt` - dans le block de rendu (lignes 72-76)

### Le probleme
Quand la souris survole un item, le tooltip est rendu a chaque frame. Le tooltip Minecraft reconstruit la liste des lignes et fait le rendu texte chaque frame.

### Impact
- Mineur, le rendu tooltip Minecraft est deja optimise
- Peut causer un leger overhead CPU sur les items avec beaucoup d'infos (enchantements, lore)

### Solution recommandee
- Cacher le contenu du tooltip et ne le recalculer que quand l'ItemStack change
- Le rendu visuel doit quand meme etre fait chaque frame (position souris)

### Gain estime
- Negligeable

---

## 7. BASSE - Pas de frame skipping

### Ou
`ComposeGui.kt` - methode `render()`

### Le probleme
Compose rend la scene a chaque frame Minecraft sans verifier si une recomposition est necessaire. Si l'UI est statique (pas de changement de donnees, pas d'animation), le rendu est quand meme execute.

### Impact
- Tout le pipeline (scroll decay, render surface, blit) tourne meme quand rien ne change
- Faible impact unitaire mais cumulatif

### Solution recommandee
- Verifier si la scene a des frames invalides (`scene.hasInvalidations()`)
- Ne rendre que si necessaire
- Garder le dernier frame rendu comme cache

### Gain estime
- Reduction de 50-90% du cout GPU quand l'UI est idle

---

## 8. INFO - Taille du JAR (~116 Mo)

### Le probleme
Le JAR embarque les natives Skiko pour **6 plateformes** :
- Windows x64 + ARM64
- macOS x64 + ARM64
- Linux x64 + ARM64

Chaque native fait ~15-20 Mo. Donc ~100 Mo sont des natives dont seule 1 sera utilisee.

### Impact
- Temps de telechargement plus long
- Espace disque
- Pas d'impact sur les performances a runtime

### Solutions possibles
1. **Builds separes par plateforme** : un jar par OS (composite-windows.jar, composite-macos.jar...)
2. **Detection au runtime** : ne charger que la native de la plateforme courante (deja fait par Skiko, mais les fichiers sont quand meme dans le jar)
3. **Natives externes** : telecharger les natives a la premiere execution

### Recommandation
Garder le fat JAR pour la simplicite de distribution. 116 Mo est acceptable pour un mod qui embarque un framework UI complet.

---

## Plan d'optimisation prioritaire

### Phase 1 - Gains majeurs (effort faible)
1. **Nettoyage GPU** : ajouter `close()` a SkiaSurface (~30 min)
2. **Flag d'initialisation** pour ComposeHud (~10 min)

### Phase 2 - Gains significatifs (effort moyen)
3. **Cache des vertex buffers** pour Texture/Item (~2h)
4. **Frame skipping** quand l'UI est idle (~1h)

### Phase 3 - Gain majeur (effort eleve)
5. **Elimination du double blit** : rendre directement sur le FBO Minecraft (~4h)
   - C'est le changement le plus impactant mais aussi le plus risque
   - Necessite de tester sur toutes les plateformes

---

## Metriques cles a surveiller

| Metrique | Comment mesurer | Valeur cible |
|---|---|---|
| FPS avec Pokematos ouvert | F3 dans Minecraft | > 60 FPS |
| VRAM utilisee | GPU-Z ou Activity Monitor | Stable dans le temps |
| Frame time du rendu Compose | Profiler GPU | < 2ms par frame |
| Allocations par frame | JVM profiler | Minimales |

---

## Notes sur la compatibilite

Toutes les optimisations proposees sont **retro-compatibles** :
- L'API publique (Components, ComposeScreen, ComposeHud) ne change pas
- Les mods qui dependent de Composite (Pokematos, Wondertrade, GTS...) n'ont pas besoin d'etre modifies
- Les optimisations sont transparentes pour les utilisateurs du framework
