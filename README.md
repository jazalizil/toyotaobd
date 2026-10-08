# Toyota OBD

Application Android (Kotlin, Jetpack Compose) qui se connecte en Bluetooth à un adaptateur
OBD-II **ELM327** pour lire les données d'un véhicule Toyota ou Lexus.

## Fonctionnalités

- **Connexion** : liste des appareils appairés (les adaptateurs OBD probables apparaissent en premier),
  recherche des appareils à proximité, choix du protocole (par défaut : CAN 11 bits 500k en
  priorité, puis recherche automatique).
- **Données** : tableau de bord en direct (régime, vitesse, températures, corrections de mélange,
  MAF, charge, niveau de carburant, tension...) et, sur les hybrides Toyota/Lexus, l'état de la
  batterie et du système hybride (voir ci-dessous). Seuls les PID déclarés par le véhicule sont interrogés.
  La **consommation instantanée** (L/100 km) est calculée : débit de carburant (PID 5E, en L/h)
  rapporté à la vitesse ; sans PID 5E (Lexus UX, par exemple), le débit est estimé d'après le débit
  d'air MAF (14,7 g d'air par g d'essence, 745 g/L : quelques pour cent d'écart). Pas de valeur
  sous 5 km/h.
  Il est organisé en trois onglets : **infos principales** (régime, vitesse, charge et puissance
  ou courant de la batterie hybride, carburant, consommation, angle du volant, pédale
  d'accélérateur), **infos hybrides**
  (le reste du système hybride) et **infos thermiques** (moteur à essence : charge, papillon,
  débit d'air, corrections de mélange, températures...). Un onglet sans valeur supportée n'est
  pas affiché ; on change d'onglet en le touchant (pas de balayage, réservé à la lecture des courbes). Le tableau de bord est **paramétrable** (bouton « Personnaliser ») : chaque valeur s'affiche en
  carte *Valeur* (chiffre et tendance), *Courbe* (graduations, min/max, lecture au doigt) ou *Jauge*
  (position dans la plage usuelle ; pour l'angle du volant, un volant dessiné qui tourne en
  suivant les lectures), sur une colonne ou en largeur ; les cartes se réordonnent et se
  masquent à l'intérieur de leur section. Les courbes couvrent 1, 5 ou 15 min (réglage « Courbes »
  de « Personnaliser »).
  **Toucher une carte** ouvre la page de détail de la valeur : courbe sur 1 min, 5 min, 15 min, 1 h
  ou tout l'historique depuis le démarrage (lecture au doigt), et statistiques de la période
  (minimum et maximum datés, moyenne pondérée par le temps, médiane, écart type, amplitude,
  variation, nombre de lectures, intervalle moyen, durée couverte). L'historique commence à la
  connexion de l'adaptateur et s'efface à la déconnexion : « depuis le démarrage » ne couvre le
  trajet entier que si l'on se connecte en démarrant. La session garde 3 h par valeur (6 000
  lectures au plus, quelques Mo). Le choix est conservé ; les cartes masquées restent
  interrogées (widget, notification). Les graphiques sont tracés avec
  [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart) (courbes `LineChart`, jauges `PieChart` sur 240°).
- **Codes** : voyant moteur, codes confirmés (03), en attente (07) et permanents (0A), avec le
  calculateur émetteur (moteur, hybride, boîte), et effacement (04) avec confirmation.
- **Toyota** : lecture de PID constructeur (services 21/22) sur un calculateur précis
  (7E0 moteur, 7E1 boîte, 7E2 hybride). Les préréglages fournis sont à vérifier selon le modèle ;
  vous pouvez ajouter vos propres PID (en-tête, commande, formule type Torque `((A*256)+B)/80`).
- **Console** : envoi de commandes AT/OBD brutes et journal de tout le trafic avec l'adaptateur.

## Données hybrides Toyota / Lexus

Elles s'ajoutent au tableau de bord (charge et puissance de la batterie dans les infos principales,
le détail dans la section « Infos hybrides ») et au widget. À la connexion, chaque requête constructeur est
essayée une fois : seules les valeurs auxquelles le véhicule répond sont affichées, les autres
n'apparaissent nulle part. Les températures et autres valeurs lentes sont lues un cycle sur cinq.

| Famille | Calculateur | Valeurs |
|---|---|---|
| Toutes (mode 01) | diffusion 7DF | charge de la batterie hybride (PID 5B) |
| **TNGA** ≈ 2016+ : Lexus UX 250h, Prius 4, C-HR, Corolla, RAV4… | 7D2, service 22 | tension, courant et puissance de la batterie HV, charge min/max, régime et couple du moteur électrique, freinage régénératif, VL/VH (avant/après élévation), onduleurs MG1/MG2, convertisseur, liquide de l'onduleur, températures et ventilateur de la batterie, huile de la transmission, compresseur de climatisation |
| TNGA, batterie lithium | 747, service 22 | tension min/max des éléments et écart |
| **HSD 3** ≈ 2009-2018 : Prius 3, Auris, Yaris, CT 200h, Prius+ | 7E2, service 21 | tension et courant de la batterie HV, régime/couple/température de MG1 et MG2, onduleurs, VL/VH, convertisseur, liquide de l'onduleur, températures et ventilateur de la batterie, tension min/max des blocs et écart, résistance interne max, limites Win/Wout, écart de charge |
| TNGA et HSD 3 | 7B0 (freinage), service 21 | angle du volant |

- **Sources** : signalsets [OBDb](https://github.com/OBDb) (Lexus UX, Corolla, C-HR, RAV4, Prius)
  et listes Torque de PriusChat. Les décodeurs sont testés sur des trames réelles : Lexus UX 250h
  ([OBDb Lexus-UX](https://github.com/OBDb/Lexus-UX)) et Auris hybride
  ([ELM327-emulator](https://github.com/Ircama/ELM327-emulator)).
- **Courant et puissance** : positifs en décharge (traction), négatifs en recharge (freinage,
  moteur thermique).
- **Angle du volant** : supposé positif à gauche, comme le capteur d'angle Toyota sur le CAN ; le
  sens de rotation du volant dessiné est à vérifier sur la voiture (`STEERING_CLOCKWISE` dans
  `ui/SteeringWheel.kt`, commun à l'application et au widget).
- **Sur l'UX 250h** (batterie NiMH), la charge vient du PID 5B ; la copie 1F5B du calculateur 7D2
  n'est lue que si le PID 5B manque. Le calculateur 747 ne répond pas, et on ne sait pas encore
  si le « moteur électrique » de 10A1 est MG2 (probable). D'après OBDb, l'UX ne fournit ni
  courant DC/DC, ni températures max/min de la batterie (1829/182A), ni données HSD 3.
- **Pour les calculateurs hors 7E0-7E7** (7D2, 747), le filtre de réception (`AT CRA`) et le
  contrôle de flux (`AT FC SH/SD/SM`) sont posés le temps de la lecture, puis rétablis.
- **Non pris en charge** : Prius 2 (NHW20, calculateur 7E3) ; PID 9A (aucune Toyota ne semble le
  fournir, 1F9A le remplace) ; informations d'état (READY, position du levier, mode de conduite),
  car les cartes n'affichent que des nombres ; pression du maître-cylindre (aucune source vérifiée).
- Certains adaptateurs bas de gamme gèrent mal les calculateurs hybrides : sur PriusChat, un
  propriétaire de Prius rechargeable attribue un passage en mode dégradé à un clone ELM327.
  Préférez un adaptateur reconnu.

## Widget « Tableau de bord OBD » (téléphone et Android Auto)

Widget [Jetpack Glance](https://developer.android.com/develop/ui/compose/glance) qui affiche toutes
les valeurs du tableau de bord en direct avec leur tendance, plus l'adaptateur connecté et la
tension batterie. Il reprend les onglets de l'application (principales, hybrides, thermiques) :
toucher un onglet change les valeurs affichées sans ouvrir l'application, et chaque widget retient
son onglet.
Il suit les [recommandations Google pour les widgets en voiture](https://developer.android.com/design/ui/cars/guides/flows/widgets) :

- **taille exacte** (`SizeMode.Exact`) : la grille s'adapte et remplit tout le widget ;
- **pas de défilement** : si toutes les valeurs de l'onglet ne tiennent pas, les plus importantes
  s'affichent avec une tuile « +N » ;
- **volant** : la tuile « Volant » montre un volant tourné de l'angle lu, dessiné comme dans
  l'application, mais sans animation (l'image est redessinée à chaque mise à jour, au plus toutes
  les 2 s) ;
- **tendances** : chaque autre tuile montre l'évolution de sa valeur sur la durée des courbes de
  l'application. Elle est placée sous la valeur si la tuile est assez haute, sinon derrière, en pâle.
  Un widget ne peut pas afficher de vue : MPAndroidChart la dessine hors écran en image, au plus
  toutes les 2 s, à une définition réduite si nécessaire (mémoire limitée des widgets) ;
- **couleurs dynamiques** du système (Android 12+), thème clair et sombre ;
- touché, il ouvre l'application.

Ajout : appui long sur l'écran d'accueil > Widgets > Toyota OBD. Google prépare des widgets dans
Android Auto (fuites et analyses de versions en 2026), mais rien n'est encore officiel pour les
applications tierces : en voiture, utilisez plutôt l'application Android Auto ci-dessous.

## Android Auto

L'application s'affiche aussi sur l'écran de la voiture, avec l'[Android for Cars App
Library](https://developer.android.com/training/cars/apps) (version 1.7.0). Android Auto n'affiche
que des gabarits imposés (listes, onglets, messages), pas les écrans du téléphone :

- **Non connecté** : un message et le bouton « Choisir l'adaptateur » (adaptateurs appairés au
  téléphone, puis simulateurs des builds debug). La connexion se lance depuis la voiture ; les
  autorisations Bluetooth, elles, se donnent sur le téléphone.
- **Connecté** : un onglet par section (principales, hybrides, thermiques). Chaque onglet est un
  **carrousel** de ses valeurs : une seule à la fois, en plein écran, et deux flèches (← →) pour
  passer à la précédente ou à la suivante (on fait le tour). L'écran d'une valeur montre son nom, sa
  valeur en grand et sa courbe sur la durée des courbes du tableau de bord (le volant tourné pour
  l'angle du volant), et à côté : valeur actuelle et position (« 3/14 »), minimum et maximum datés,
  moyenne pondérée par le temps sur cette durée. Le carrousel suit l'ordre et les cartes affichées
  du tableau de bord (« Personnaliser » sur le téléphone) ; chaque onglet retient sa valeur, même
  si une carte est masquée ou déplacée. Les valeurs se rafraîchissent au plus toutes les 2 s, sans
  faire bouger l'écran (un panneau ne défile pas).
- Hôtes plus anciens : sans onglets (API voiture < 6), un seul carrousel de toutes les valeurs ;
  sans image (API < 4), le détail seul.

**Catégorie et installation.** Android Auto n'accepte que certaines catégories d'applications
(navigation, points d'intérêt, IoT, météo...) ; aucune ne correspond au diagnostic OBD.
L'application se déclare « IoT » (appareils connectés), la plus proche : c'est adapté à un usage
personnel, pas à une publication sur Google Play telle quelle. Pour l'afficher dans la voiture :

1. Android Auto sur le téléphone : Réglages > Android Auto > toucher dix fois « Version » pour
   activer le mode développeur, puis menu ⋮ > Paramètres pour les développeurs > cocher
   **Sources inconnues** (applications installées hors Play Store).
2. Installer l'APK (debug ou release) sur le téléphone, brancher la voiture.

**Tester sans voiture** avec le [Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu)
(SDK Manager > SDK Tools > Android Auto Desktop Head Unit Emulator) : sur le téléphone, menu ⋮
d'Android Auto > « Démarrer le serveur de l'unité principale », puis sur l'ordinateur
`adb forward tcp:5277 tcp:5277` et `$ANDROID_HOME/extras/google/auto/desktop-head-unit`. Les builds
debug acceptent tout hôte (DHU compris), les builds release seulement Android Auto.

**Connexion en arrière-plan.** Pour que le widget reste à jour téléphone verrouillé ou en voiture,
la connexion est portée par l'application (`ObdSession`) et un service de premier plan
(notification « Connecté · … » avec les valeurs clés et un bouton **Déconnecter**). L'interrogation
continue donc tant que l'adaptateur est connecté, ce qui consomme de la batterie : déconnectez-vous
en fin de trajet. Elle n'est suspendue que lorsque l'onglet Console est affiché.

## Utilisation

1. Branchez l'adaptateur sur la prise OBD (sous le volant) et mettez le contact.
2. Dans **Paramètres > Bluetooth** d'Android, appairez l'adaptateur (code PIN : `1234` ou `0000`).
3. Ouvrez l'application, accordez les autorisations Bluetooth, touchez l'adaptateur dans la liste.

## Mode développement : adaptateurs simulés

Les builds **debug** affichent en tête de l'écran de connexion des adaptateurs ELM327 simulés,
utilisables sans Bluetooth ni voiture (émulateur Android compris). Le simulateur reproduit le
comportement d'un vrai ELM327 v1.5 : commandes AT, écho, espaces, en-têtes, adressage `ATSH`,
filtre `ATCRA` (sans lui, seules les réponses 7E8-7EF sont affichées), recherche du protocole
(`SEARCHING...`), trames CAN ISO-TP ou messages ISO 9141, et délais réalistes.

| Scénario | Ce qu'il permet de tester |
|---|---|
| Toyota hybride (CAN) | 3 calculateurs (moteur 7E8, boîte 7E9, hybride 7EA), cycle de conduite de 90 s (moteur thermique coupé à basse vitesse), codes P0420 / P0171 en attente / P0A80, codes permanents conservés après effacement, VIN multi-trames, PID constructeur `221E1C`, `2182` (7E1) et `2101` (7E0, multi-trames), données hybrides HSD 3 (7E2, service 21 ; batterie qui se décharge à l'accélération et se recharge au freinage), angle du volant (7B0) |
| Lexus UX 250h (TNGA) | charge en mode 01 (5B), données hybrides sur 7D2 (service 22, réponses multi-trames hors 7E8-7EF), angle du volant (7B0), pas de calculateur 747 |
| Toyota ancienne (ISO 9141-2) | un seul calculateur, en-têtes ISO avec somme de contrôle, 4 codes répartis sur 2 messages, pas de VIN ni de PID constructeur |
| Contact coupé | `UNABLE TO CONNECT` pendant l'initialisation |
| Liaison instable | coupure de la liaison au bout de 20 s (« Liaison perdue ») |

Le choix du protocole est respecté : forcer « ISO 9141-2 » sur la voiture CAN renvoie
`BUS INIT: ...ERROR`, comme un vrai adaptateur.

Le code du simulateur est dans `app/src/debug/` et n'est **pas inclus dans les builds release**
(`app/src/release/.../DevDevices.kt` y déclare une liste vide). Ses tests sont dans `app/src/testDebug/`.

## Compiler

Prérequis : JDK 17 et le SDK Android (API 36). MPAndroidChart vient de [JitPack](https://jitpack.io)
(dépôt déclaré dans `settings.gradle.kts`). Ouvrez le dossier dans Android Studio, ou :

```bash
./gradlew assembleDebug         # APK : app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest     # tests unitaires du protocole (sans Android)
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

```
app/src/main/java/com/example/toyotaobd/
├── obd/              Kotlin pur, sans dépendance Android (testé unitairement)
│   ├── Elm327.kt         échange commande/réponse avec l'ELM327, verrou, délais
│   ├── ResponseParser.kt nettoyage des réponses, en-têtes CAN/ISO, réassemblage ISO-TP
│   ├── ObdClient.kt      initialisation, PID, codes défaut, VIN, PID constructeur
│   ├── Pids.kt, Dtc.kt   décodage SAE J1979 / J2012
│   ├── Formula.kt        formules de conversion des PID personnalisés
│   ├── Toyota.kt         calculateurs et préréglages Toyota/Lexus
│   └── ToyotaHybrid.kt   données hybrides du tableau de bord (TNGA 7D2/747, HSD 3 7E2) et angle du volant (7B0)
├── bluetooth/        Bluetooth classique (RFCOMM/SPP), recherche, permissions
├── session/          ObdSession : connexion, interrogation en continu, historique, console (Kotlin pur)
├── service/          service de premier plan et action « Déconnecter »
├── widget/           widget Glance et calcul de sa grille
├── car/              Android Auto : service, tableau de bord et choix de l'adaptateur (gabarits)
├── data/             PID personnalisés, disposition du tableau de bord et leur sauvegarde
├── dev/              SimulatedDevice (la liste vient de DevDevices, par variante)
└── ui/               ViewModel, écrans Compose, cartes du tableau de bord, graphiques MPAndroidChart (Charts.kt)
app/src/debug/java/com/example/toyotaobd/dev/
├── DevDevices.kt     scénarios proposés dans l'écran de connexion
└── sim/              véhicules simulés, émulateur ELM327, transport sans Bluetooth
```

Après la détection du protocole, les en-têtes sont activés (`ATH1`) : on sait ainsi quel
calculateur a répondu, ce qui compte sur les hybrides où le moteur (7E8) et le calculateur
hybride (7EA) répondent tous les deux aux requêtes de diffusion.

## Limites connues

- **Bluetooth Low Energy non pris en charge.** L'application fonctionne avec les adaptateurs
  Bluetooth « classiques » (SPP) : la majorité des clones ELM327, OBDLink MX+/LX, Vgate iCar Pro
  version Bluetooth 3.0... Les modèles **BLE uniquement** (Vgate iCar Pro BLE, Veepeak BLE,
  OBDLink CX, adaptateurs vendus « compatibles iPhone ») n'apparaîtront pas ou ne se connecteront pas.
  L'interface `ObdTransport` permet d'ajouter un transport BLE (GATT) plus tard.
- **PID constructeur** : uniquement en CAN 11 bits (Toyota/Lexus ≈ 2008 et après). Les adresses
  et formules varient selon le modèle et l'année ; les octets bruts sont toujours affichés.
- **Non testé sur un véhicule réel** : le protocole est vérifié par des tests unitaires et par le
  simulateur, qui reflètent la documentation ELM327 et non un adaptateur ou une voiture réels.
  Les décodeurs hybrides sont vérifiés sur des trames relevées par d'autres (UX 250h 2023, Auris).
  Le comportement de l'application face à ces calculateurs, lui, n'a jamais été testé (contrôle de flux, délais).
- **Données hybrides** : chaque calculateur interrogé ajoute quelques commandes par cycle (en-tête,
  filtre, contrôle de flux) ; le régime et la vitesse se rafraîchissent donc un peu moins vite sur
  une hybride. Masquer une carte n'arrête pas son interrogation.
- Les descriptions de codes défaut ne couvrent que les codes génériques les plus courants.
- **Widget** : vérifié sur l'écran d'accueil d'un émulateur et par des tests unitaires (calcul de
  la grille, session) ; Android Auto n'affiche pas encore les widgets des applications tierces.
- **Android Auto** : essayé sur un hôte en API voiture 8. L'hôte limite une tâche à 5 gabarits :
  le tableau de bord est empilé pendant la connexion puis retiré, et le message de connexion ne
  change pas d'une étape à l'autre : connexions et déconnexions répétées n'épuisent pas ce quota.
  Le carrousel garde les mêmes titres de lignes pour toutes les valeurs (le nom est dans le texte
  et l'image) afin qu'une flèche ne soit qu'un rafraîchissement ; il n'a pas encore été essayé dans
  Android Auto (ni DHU ni voiture) : si l'hôte ferme l'application après quelques flèches, c'est
  ce quota.
- Sans l'autorisation Bluetooth (simulateur en debug sur Android 14+), le service de premier plan
  n'est pas démarré : la connexion vit tant que le processus de l'application vit.
