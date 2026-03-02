# GraphHopper 11 avec datafeed trafic

Ce dépôt est une copie de [GraphHopper 11.0](https://github.com/graphhopper/graphhopper/releases/tag/11.0) avec l’endpoint **POST /datafeed** et **GET /datafeed/roads** pour alimenter le graphe en données trafic (vitesses) en temps réel.

## Emplacement

Le projet se trouve dans : `chtrafic/graphhopper-traffic/`.  
Le bundle Maps avec couche trafic est un **clone patché** de [graphhopper-maps](https://github.com/graphhopper/graphhopper-maps), à placer au même niveau : `chtrafic/graphhopper-maps/`.

Pour déplacer graphhopper-traffic à la racine de vos projets :

```bash
mv /home/epascal/Projects/chtrafic/graphhopper-traffic /home/epascal/Projects/
```

## Compilation

**Prérequis :** Java 21 et Maven 3.

- **Sans couche trafic sur /maps** (bundle npm par défaut) :

```bash
cd graphhopper-traffic
mvn clean package -DskipTests
```

- **Avec couche trafic sur la page /maps** (bundle local patché) : cloner graphhopper-maps au même niveau que graphhopper-traffic, puis compiler avec le profil `local-maps` :

```bash
cd chtrafic
git clone https://github.com/graphhopper/graphhopper-maps.git
cd graphhopper-traffic
mvn clean package -DskipTests -Plocal-maps
```

Le profil `-Plocal-maps` utilise le tgz déjà présent dans `web-bundle/target/` (à préparer avec `npm run build && npm pack --pack-destination=../graphhopper-traffic/web-bundle/target` dans le clone). Le bundle patché ajoute une couche « Trafic (vitesse) » (toggle dans les options de la carte) affichant les segments de GET /datafeed/roads avec un dégradé rouge–orange–vert.

**Important :** pour conserver la case « Trafic (vitesse) » sur /maps, **toujours** lancer `mvn package` avec `-Plocal-maps`. Sans ce profil, le JAR reprend le bundle npm par défaut et la case disparaît.

Le JAR exécutable (fat JAR) est généré dans :

`web/target/graphhopper-web-11.0-SNAPSHOT.jar`

## Lancement

Exemple avec des données OSM (remplacer par votre fichier PBF) :

```bash
java -jar web/target/graphhopper-web-11.0-SNAPSHOT.jar server config-example.yml
# avec données OSM :
# java -Ddw.graphhopper.datareader.file=/chemin/vers/region.osm.pbf -jar web/target/graphhopper-web-11.0-SNAPSHOT.jar server config-example.yml
```

Puis envoyer des données trafic :

```bash
curl -X POST -H "Content-Type: application/json" -d '[{"id":"1","points":[[6.14,46.20]],"value":30,"value_type":"speed","mode":"REPLACE"}]' http://localhost:8989/datafeed
```

## Format du datafeed

- **POST /datafeed**
- Body JSON : tableau d’entrées.
- Chaque entrée : `id`, `points` (tableau de `[lon, lat]` en GeoJSON), `value` (vitesse en km/h), `value_type` (ex. `"speed"`), `mode` (ex. `"REPLACE"`).
- Seul le **premier point** de chaque entrée est utilisé pour trouver l’arête la plus proche ; la vitesse est appliquée à cette arête.
- **GET /datafeed/roads** : GeoJSON FeatureCollection des segments (propriétés `value`, `relative_speed`, `color`). Le coefficient **relative_speed** utilise `max_speed` sur l'edge si `graph.encoded_values` contient `max_speed`.

Compatible avec le script `push-graphhopper.ts` du projet chtrafic (tuiles TomTom).

## Fichiers modifiés / ajoutés

- `web/src/main/java/com/graphhopper/application/resources/DataFeedResource.java` — POST /datafeed et GET /datafeed/roads (relative_speed / max_speed).
- `web/src/main/java/com/graphhopper/application/GraphHopperApplication.java` — enregistrement de `DataFeedResource`.
- `config-example.yml` — `graph.encoded_values` inclut `max_speed` pour le coefficient de ralentissement.
- `web-bundle/pom.xml` — propriété `graphhopper.maps.useLocal`, profil `local-maps` pour build du clone graphhopper-maps.
- Clone **graphhopper-maps** : `package.json` (name/version pour tgz local), `src/layers/UseTrafficLayer.tsx`, actions/stores/MapOptions pour le toggle « Trafic (vitesse) » sur /maps.

## Note CH et trafic

Les vitesses sont modifiées en mémoire. En mode Contraction Hierarchies (CH), les routes ne reflètent ces changements qu’après une nouvelle préparation (ou en utilisant le mode flexible). Voir `chtrafic/docs/GRAPHHOPPER-DOCKER.md` pour les détails.
