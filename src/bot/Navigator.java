package bot;

import lux.*;

import javax.swing.text.html.Option;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;

public class Navigator {
  final private String TAG = "Navigator";

  final private GameState gameState;
  final private GameMap gameMap;

  private static int[] dx = {-1, 0, 1, 0, 0}, dy = {0, -1, 0, 1, 0};

  public Navigator(GameState gameState) {
    this.gameState = gameState;
    this.gameMap = gameState.map;
  }

  private int convertCoordinateToInt(int x, int y) {
    return y * gameMap.width + x;
  }

  private Position convertIntToCoordinate(int pos) {return new Position(pos % gameMap.width, pos / gameMap.width);}

  private HashSet<Integer> cityTileLocationsForPlayer(Player p) {
    HashSet<Integer> locs = new HashSet<>();
    p.cities.values().forEach(city -> {
      city.citytiles.forEach(cityTile -> {
        locs.add(convertCoordinateToInt(cityTile.pos.x, cityTile.pos.y));
      });
    });
    return locs;
  }

  private HashSet<Integer> currentObstacles(ArrayList<Unit> ignoreUnits) {
    Player player = gameState.players[gameState.id];
    Player opponent = gameState.players[(gameState.id + 1) % 2];

    HashSet<Integer> goodCityTiles = cityTileLocationsForPlayer(player),
            badCityTiles = cityTileLocationsForPlayer(opponent);

    HashSet<Integer> obstacles = new HashSet<>();

    obstacles.addAll(badCityTiles);

    player.units.forEach(unit -> {
      int posId = convertCoordinateToInt(unit.pos.x, unit.pos.y);
      if (!ignoreUnits.contains(unit) && !goodCityTiles.contains(posId)) {
        obstacles.add(posId);
      }
    });

    opponent.units.forEach(unit -> {
      if (!ignoreUnits.contains(unit)) {
        obstacles.add(convertCoordinateToInt(unit.pos.x, unit.pos.y));
      }
    });

    return obstacles;
  }

  private void generateFlowGraphForResourceRouting(ArrayList<Unit> units, ArrayList<Cell> resources, int timeLayers,
                                                   MinCostMaxFlow flow) {

    HashSet<Integer> obstacles = currentObstacles(/*ignore these=*/units);
    HashSet<Integer> goodCityTiles = cityTileLocationsForPlayer(gameState.players[gameState.id]);

    int cellCount = gameMap.width * gameMap.height;
    for (int t = 0; t < timeLayers; t++) {
      for (int x = 0; x < gameMap.width; x++) {
        for (int y = 0; y < gameMap.height; y++) {
          int offset = t * cellCount * 2;
          int cellId = convertCoordinateToInt(x, y);

          if (obstacles.contains(cellId))
            continue;

          int selfCap = goodCityTiles.contains(cellId) ? Integer.MAX_VALUE/2 : 1;
          flow.add(offset + 2 * cellId, offset + 2 * cellId + 1, selfCap, 0);

          // Add a flow source if there is an available unit there
          if (t == 0) {
            final int X = x, Y = y;
            Optional<Unit> unit = units.parallelStream().filter(u -> u.pos.x == X && u.pos.y == Y).findAny();
            if (unit.isPresent()) {
              MinCostMaxFlow.Edge e = flow.add(flow.s, 2 * cellId, 1, 0);
              e.setMetadata(unit.get().id);
            }
          }

          // Check all four adjacent squares + remaining still
          // Add an edge if it's a valid square and not an obstacle
          for (int k = 0; k < 5; k++) {
            int xx = x + dx[k], yy = y + dy[k];
            if (xx < 0 || xx >= gameMap.width || yy < 0 || yy >= gameMap.height)
              continue;

            int tmpId = convertCoordinateToInt(xx, yy);
            if (obstacles.contains(tmpId))
              continue;

            int nextOffset = (t + 1) * cellCount * 2;
            int nextPosition = (t == timeLayers-1) ? tmpId : 2 * tmpId;
            int cost = tmpId == cellId ? 0 : 1;
            flow.add(offset + 2 * cellId + 1, nextOffset + nextPosition, 1, cost);
          }
        }
      }
    }

    // Make adjacencies for last layer
    for (int x = 0; x < gameMap.width; x++) {
      for (int y = 0; y < gameMap.height; y++) {
        int offset = timeLayers * cellCount * 2;
        int cellId = convertCoordinateToInt(x, y);

        if (obstacles.contains(cellId))
          continue;

        final int X = x, Y = y;
        Optional<Cell> closestResource = resources.stream().min((a, b) -> {
          double d1 = a.pos.distanceTo(gameMap.getCell(X,Y).pos),
                  d2 = b.pos.distanceTo(gameMap.getCell(X,Y).pos);
          return Double.compare(d1,d2);
        });
        int closestDist = Integer.MAX_VALUE;
        if(closestResource.isPresent()) {
          closestDist = (int) closestResource.get().pos.distanceTo(gameMap.getCell(x,y).pos);
        }

        int selfCap = goodCityTiles.contains(cellId) ? Integer.MAX_VALUE/2 : 1;
        if(closestDist != Integer.MAX_VALUE)
          flow.add(offset+cellId, flow.t, selfCap, Math.max(0, (closestDist-1)*50));

        for (int k = 0; k < 5; k++) {
          int xx = x + dx[k], yy = y + dy[k];
          if (xx < 0 || xx >= gameMap.width || yy < 0 || yy >= gameMap.height)
            continue;

          int tmpId = convertCoordinateToInt(xx, yy);
          if (obstacles.contains(tmpId))
            continue;

          int cost = tmpId == cellId ? 0 : 1;
          flow.add(offset + cellId, offset + tmpId, Integer.MAX_VALUE/2, cost);
        }
      }
    }
  }

  private int followPath(int idx, MinCostMaxFlow flow) {
    for (MinCostMaxFlow.Edge e : flow.adj[idx]) {
      if (e.flow > 0) {
        if (e.cost > 0) {
          return e.v2;
        } else {
          return followPath(e.v2, flow);
        }
      }
    }
    return -1;
  }

  private ArrayList<String> readFlowGraphForMoves(ArrayList<Unit> units, int timeLayers, MinCostMaxFlow flow) {
    ArrayList<String> actions = new ArrayList<>();
    int cellCount = gameMap.width * gameMap.height;
    for(MinCostMaxFlow.Edge e : flow.adj[flow.s]) {
      if (e.flow > 0 && e.metadata != null) {
        Unit unit = units.parallelStream().filter(u->u.id==e.metadata).findAny().get();

        int end = followPath(e.v2, flow);
        if (end == -1) {
          continue;
        }

        int node = end;
        if (node >= timeLayers * 2 * cellCount) {
          node -= timeLayers * 2 * cellCount;
        } else {
          node %= 2 * cellCount;
          node /= 2;
        }

        Position destination = convertIntToCoordinate(node);
        Direction dir = unit.pos.directionTo(destination);

        actions.add(unit.move(dir));
      }
    }

    return actions;
  }

  public ArrayList<String> generateRoutesToResources(ArrayList<Unit> units, ArrayList<Cell> resources) {
    // 5 time layers plus 1 untimed layer
    int timeLayers = 5;
    int cellCount = gameMap.width * gameMap.height;
    MinCostMaxFlow flow = new MinCostMaxFlow(cellCount * timeLayers * 2 + cellCount);

    generateFlowGraphForResourceRouting(units, resources, timeLayers, flow);

    long[] results = flow.flow();

    return readFlowGraphForMoves(units, timeLayers, flow);
  }


}
