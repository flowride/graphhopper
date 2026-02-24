# GraphHopper 11 avec datafeed trafic

Ce dépôt est une copie de [GraphHopper 11.0](https://github.com/graphhopper/graphhopper/releases/tag/11.0) avec l’endpoint **POST /datafeed** ajouté pour alimenter le graphe en données trafic (vitesses) en temps réel.

## Emplacement

Le projet se trouve dans : `chtrafic/graphhopper-traffic/`  
Pour le déplacer à la racine de vos projets :

```bash
mv /home/epascal/Projects/chtrafic/graphhopper-traffic /home/epascal/Projects/
```

## Compilation

**Prérequis :** Java 21 et Maven 3.

```bash
cd graphhopper-traffic
mvn clean package -DskipTests
```

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

Compatible avec le script `push-graphhopper.ts` du projet chtrafic (tuiles TomTom).

## Fichiers modifiés / ajoutés

- `web/src/main/java/com/graphhopper/application/resources/DataFeedResource.java` — resource JAX-RS pour POST /datafeed.
- `web/src/main/java/com/graphhopper/application/GraphHopperApplication.java` — enregistrement de `DataFeedResource`.

## Note CH et trafic

Les vitesses sont modifiées en mémoire. En mode Contraction Hierarchies (CH), les routes ne reflètent ces changements qu’après une nouvelle préparation (ou en utilisant le mode flexible). Voir `chtrafic/docs/GRAPHHOPPER-DOCKER.md` pour les détails.
