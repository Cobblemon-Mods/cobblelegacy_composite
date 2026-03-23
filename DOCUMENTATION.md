# Composite - Documentation Technique

## Qu'est-ce que Composite ?

Composite est un **mod Fabric client-side** (uniquement cote client) pour Minecraft 1.21.1. C'est une **bibliotheque** (library mod) qui permet aux autres mods d'utiliser **Jetpack Compose** et **Material 3** pour creer des interfaces utilisateur (UI) dans Minecraft.

En gros, au lieu de coder des menus Minecraft avec le systeme natif (Screens, Widgets, GuiGraphics...), les mods qui dependent de Composite peuvent utiliser le meme framework UI qu'Android et les apps desktop Kotlin : **Jetpack Compose**.

---

## A quoi ca sert concretement ?

### Le probleme
Creer des interfaces dans Minecraft est fastidieux :
- Le systeme de GUI natif est bas-niveau (dessiner pixel par pixel, gerer les events manuellement)
- Pas de layout automatique (flexbox, grids, etc.)
- Pas de theming ou de design system
- Le code UI devient vite un plat de spaghetti

### La solution Composite
Composite integre **Jetpack Compose Desktop** dans Minecraft, ce qui donne acces a :
- **Material 3** : composants modernes (boutons, cards, sliders, textes styles...)
- **Layout declaratif** : Row, Column, Box, LazyColumn...
- **Recomposition automatique** : l'UI se met a jour quand les donnees changent
- **Animations** : transitions, effets, scroll fluide
- **Theming** : couleurs, typographie, formes coherentes

### Qui l'utilise ?
Les autres mods CobbleLegacy, notamment :
- **cobblelegacy-pokematos** : le Pokematos (UI principale du modpack)
- **cobblelegacy_wondertrade** : systeme de Wonder Trade
- **cobblelegacy_gts** : GTS (Global Trade Station)
- **cobblelegacy_darkauction** : Dark Auction
- Et potentiellement tous les autres mods CobbleLegacy avec une UI client

---

## Architecture Generale

### Vue d'ensemble

```
Minecraft (OpenGL / LWJGL / GLFW)
    |
    v
Composite (Pont entre Minecraft et Compose)
    |
    +-- Skia Layer (Rendu GPU)
    |   +-- SkiaContext : gere le contexte GPU Skia
    |   +-- SkiaSurface : surface de dessin (framebuffer OpenGL)
    |
    +-- Core Layer (Integration Compose)
    |   +-- ComposeGui : moteur principal (input, rendu, scene Compose)
    |   +-- ComposeScreen : wrapper pour les ecrans plein-ecran
    |   +-- ComposeHud : wrapper pour les overlays HUD
    |
    +-- Components Layer (API publique)
    |   +-- Components : facade avec les composables reutilisables
    |   +-- Texture : rendu de textures Minecraft dans Compose
    |   +-- Item : rendu d'ItemStacks dans Compose
    |   +-- AssetImage : chargement et cache d'images PNG
    |
    +-- i18n Layer (Internationalisation)
        +-- translate() : traductions Minecraft dans Compose
        +-- TranslatableText : composant texte traduit
        +-- ComponentExt : conversion Style Minecraft -> Compose
```

---

## Comment ca marche (flux de rendu)

### 1. Demarrage du mod
Quand Minecraft demarre :
1. Fabric appelle `Composite.onInitializeClient()`
2. La propriete `skiko.macos.opengl.enabled` est activee (support macOS)
3. Quand le client est pret, `SkiaContext.initialize()` cree le contexte GPU Skia
4. Les commandes de test sont enregistrees (`/composite test ...`)

### 2. Ouverture d'un ecran Compose
Quand un mod ouvre un ecran (ex: le Pokematos) :
1. Un `ComposeScreen` est cree avec le contenu Compose
2. Il cree un `ComposeGui` qui contient :
   - Une `SkiaSurface` (surface de rendu GPU)
   - Une `CanvasLayersComposeScene` (scene Compose)
3. `init()` est appele : la surface est dimensionnee, l'echelle GUI est calculee

### 3. Boucle de rendu (chaque frame)
A chaque image (60+ fois/seconde) :
1. Les events souris/scroll sont envoyes a la scene Compose
2. Le scroll avec inertie est calcule (decay exponentiel)
3. La surface Skia capture le framebuffer principal de Minecraft
4. La scene Compose se rend sur le canvas Skia
5. L'etat OpenGL est nettoye (`resetGLAll`, `flush`)
6. La texture Skia est blittee par-dessus le rendu Minecraft
7. Les appels de rendu Minecraft enregistres (textures, items) sont executes

### 4. Gestion des inputs
- **Souris** : clic, relachement, mouvement -> events Compose (PointerEvent)
- **Scroll** : accumulation + decay exponentiel pour un scroll fluide
- **Clavier** : touches -> KeyEvent Compose, avec support backspace et IME
- **Curseur** : change selon le contexte (main, texte, fleche...)
- **Presse-papier** : utilise le clipboard Minecraft, IO asynchrone

### 5. Fermeture
- La scene Compose est fermee
- Le callback GLFW clavier est restaure

---

## Les couches en detail

### Couche Skia (Rendu GPU)

#### SkiaContext
- **Singleton** global pour tout le mod
- Contient le `DirectContext` Skia (interface GPU OpenGL)
- Initialise une seule fois au demarrage (lazy)
- `run()` execute du code dans le contexte Skia (direct, sans switch de contexte)

#### SkiaSurface
- **Une instance par ecran/HUD**
- Cree un framebuffer OpenGL (FBO) et une texture
- Skia dessine sur cette texture via `BackendRenderTarget`
- Le resultat est blit sur l'ecran Minecraft
- Gere le resize automatique quand la fenetre change de taille
- Supporte l'enregistrement d'appels de rendu Minecraft (pour items, textures)

### Couche Core (Integration Compose)

#### ComposeGui
- **Le coeur du systeme** - fait le pont entre Minecraft et Compose
- Gere tous les inputs (souris, clavier, scroll, IME)
- Convertit les coordonnees Minecraft en coordonnees Compose (avec l'echelle GUI)
- Fournit les CompositionLocals (surface, clipboard, langue)
- Scroll avec physique d'inertie (decay exponentiel)
- Densite d'affichage = `guiScale * 0.5`

#### ComposeScreen
- Etend `Screen` de Minecraft
- Delegue tout a `ComposeGui`
- Utilise par les mods pour creer des ecrans plein-ecran
- Le constructeur prend un titre et un contenu `@Composable`

#### ComposeHud
- Implemente `HudRenderCallback` de Fabric
- Delegue tout a `ComposeGui`
- Pour les overlays qui restent affiches en jeu (HUD)
- Appele a chaque frame par le systeme d'events Fabric

### Couche Components (API Publique)

#### Components (facade)
Point d'entree principal pour les mods. Expose :

- **`Item()`** : affiche un ItemStack Minecraft dans Compose
  - Supporte les decorations (durabilite, compteur, enchant)
  - Tooltip au survol
  - Redimensionnable

- **`Texture()`** : affiche une texture Minecraft
  - Par ResourceLocation ou AbstractTexture
  - Coordonnees UV configurables
  - Clipping automatique

- **`AssetImage()`** : charge et affiche une image PNG
  - Cache LRU de 64 images
  - Chargement asynchrone (pas de freeze)
  - Support ContentScale, ColorFilter, alpha

- **`TranslatableText()`** : texte avec traduction Minecraft
  - Style Material 3 complet
  - Arguments dynamiques

- **`TranslatableAnnotatedText()`** : texte riche traduit
  - Preserve gras, italique, souligne, barre, couleurs
  - Support du clic sur le texte

### Couche i18n (Internationalisation)

- `LocalLocale` : fournit la langue actuelle de Minecraft aux composables
- `translate()` : traduit une cle avec le systeme Minecraft
- `translateAnnotated()` : traduction avec styles preserves (AnnotatedString)
- `ComponentExt` : convertit les styles Minecraft (ChatFormatting) en styles Compose (SpanStyle)
- Les 16 couleurs Minecraft sont mappees vers des couleurs Compose

---

## Build et Distribution

### Comment c'est construit
- **Gradle** avec les plugins Fabric Loom, Kotlin Compose et Compose Multiplatform
- Java 21 (toolchain)
- Kotlin 2.3.0 avec le compileur Compose

### Le Fat JAR (~116 Mo)
Le jar final embarque tout :
- Le code Composite
- Toutes les dependances Compose (Material 3, Foundation, UI, Runtime...)
- Les natives Skiko pour **toutes les plateformes** :
  - Windows x64 + ARM64
  - macOS x64 + ARM64 (Apple Silicon)
  - Linux x64 + ARM64
- Les dependances AndroidX (Collection, Lifecycle, SavedState...)
- Kotlinx (Coroutines, Serialization, DateTime)

### Dependances requises (non embarquees)
- Minecraft 1.21.1
- Fabric API (n'importe quelle version compatible)
- Fabric Language Kotlin (n'importe quelle version compatible)

---

## Commandes de test

Le mod enregistre des commandes pour tester les composants :

| Commande | Description |
|---|---|
| `/composite test texture` | Affiche une texture de bloc (dirt) en 128x128 |
| `/composite test item` | Affiche 3 items (epee diamant, pomme x8, herbe x64) |
| `/composite test asset_image` | Charge et affiche une image PNG |
| `/composite test translation` | Demontre le systeme de traduction |

---

## Plateformes supportees

| Plateforme | Support |
|---|---|
| Windows x64 | Oui (natives incluses) |
| Windows ARM64 | Oui (natives incluses) |
| macOS x64 (Intel) | Oui (natives incluses) |
| macOS ARM64 (Apple Silicon M1/M2/M3) | Oui (natives incluses + fix OpenGL) |
| Linux x64 | Oui (natives incluses) |
| Linux ARM64 | Oui (natives incluses) |

---

## Resume technique

| Element | Valeur |
|---|---|
| Nom du mod | Composite |
| ID Fabric | `composite` |
| Version | 0.5.0 |
| Minecraft | 1.21.1 |
| Kotlin | 2.3.0 |
| Compose | 1.9.3 |
| Java | 21 |
| Type | Bibliotheque client-side |
| Taille du JAR | ~116 Mo |
| Lignes de code | ~1440 lignes Kotlin |
| Fichiers source | 18 fichiers |
