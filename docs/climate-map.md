# Lumina climate map v2 (2026-10-29): climates from precipitation and temperature, by real-world analogue

Model: each region has a real-world analogue, a mean temperature (C) and annual precipitation (mm). From those:
- wet share = how often precipitation falls (mm -> share of time, capped so the world stays bright);
- precipitation is rain above 2 C, snow at or below (mountains are colder than their lowlands);
- overcast/fog shares follow humidity; clear is the rest;
- special weathers are added where the real analogue has them: sandstorm (desert), ash fall (volcanic), freezing fog (arctic).
Rules kept: sky owns the colour; fog = horizon except rain grey / fog white-grey / snow white / sandstorm orange / ash grey-brown;
only Morytania tints the sky (ambience tints from 117 HD apply everywhere); no seasons; Wilderness never clear, never snow.

| Region | Analogue | Temp C | Precip mm/yr | Clear | Partly | Overcast | Rain | Storm | Fog | Snow | Special |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Central mainland (Lumbridge, Varrock, Falador, Ardougne, Camelot, Yanille, Hosidius) | UK | 10 | 900 | 48 | 20 | 12 | 12 | 2 | 6 (mornings) | 0 | - |
| Burthorpe/Taverley, Seers, Rellekka lowlands, Zeah north | northern UK / southern Norway | 4 | 900 | 40 | 18 | 18 | 10 | 1 | 6 | 7 | - |
| Fremennik isles, Trollheim, White Wolf, Weiss, God Wars, Wintertodt, Varlamore snowy mountains | Nordics / alpine | -4 | 700 | 35 | 15 | 20 | 0 | 0 | 5 freezing | 25 | permanent snow cover |
| Kharidian desert | Arabian | 30 | 100 | 82 | 5 | 2 | 1 | 0 | 0 | 0 | sandstorm 10 |
| Karamja, Feldip, Mos Le'Harmless, Fossil Island, Ape Atoll, Tlati | tropics | 27 | 2500 | 45 | 15 | 5 | 20 (short, heavy) | 15 | 0 | 0 | - |
| Karamja volcano, Lovakengj, TzHaar surroundings | volcanic | 25 | 300 | 55 | 15 | 15 | 5 | 0 | 0 | 0 | ash fall 10 |
| Auburnvale (redwood/autumn forest) | northern US forest (user) | 8 | 1100 | 42 | 20 | 15 | 12 | 2 | 6 (mornings) | 3 (light; next to the snowy mountains) | - |
| Civitas illa Fortis, Aldarin, Sunset Coast | Mediterranean / Roman (uncertain) | 20 | 500 | 65 | 15 | 5 | 8 | 5 | 2 | 0 | - |
| Avium savannah, Quetzacalli gorge | savannah (uncertain) | 24 | 600 | 65 | 15 | 5 | 8 | 7 | 0 | 0 | - |
| Varlamore snowy mountains: see the Nordics/alpine row | | | | | | | | | | | |
| Kourend (Shayzien, Piscarilius, Woodland), Kebos | US temperate | 12 | 1000 | 45 | 20 | 12 | 13 | 3 | 7 | 0 | - |
| Kebos lowlands, Tar swamp, Lumbridge swamp | wetlands | 12 | 1200 | 30 | 20 | 20 | 12 | 3 | 15 | 0 | - |
| Arceuus | dark-magic uplands | 6 | 900 | 15 | 15 | 45 | 10 | 2 | 10 | 3 | - |
| Tirannwn (Isafdar, Prifddinas), Gnome Stronghold | temperate rainforest (Wales / Pacific NW) | 9 | 1600 | 35 | 20 | 15 | 20 | 2 | 8 | 0 | - |
| Morytania (Mort Myre, Barrows, Meiyerditch, Darkmeyer, Slepe) | gothic fenland | 8 | 1300 | 0 | 5 | 40 | 25 | 5 | 25 | 0 | sky/fog tint green |
| Wilderness | magical wasteland | 8 | - | 0 | 0 | 50 | 20 | 20 | 10 | 0 | always dim and desaturated |
| Underground, dungeons, instances | - | - | - | no weather, no sky | | | | | | | |

Not yet placed (default to the nearest listed climate by position): Zanaris and other planes (no weather), Fossil Island volcanic area (ash), Great Kourend's Mount Karuulm (volcanic).
Pattern durations: 8-24 game hours; storms shorter (4-8). Transitions: cover 15 s, precipitation 8 s after cover, fog 15 s, spatial ramp ~80 tiles.
