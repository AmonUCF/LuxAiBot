package bot;

import lux.*;

import javax.swing.text.html.Option;
import java.util.*;

public class Navigator {
  final private String TAG = "Navigator";

  final private Player player;
  final private GameState gameState;
  final private GameMap gameMap;

  // 5 time layers plus 1 untimed layer
  private final int timeLayers = 5;

  private MinCostMaxFlow flow;

  private static int[] dx = {-1, 0, 1, 0, 0}, dy = {0, -1, 0, 1, 0};

  public Navigator(GameState gameState) {
    this.gameState = gameState;
    this.gameMap = gameState.map;
    this.player = gameState.players[gameState.id];
  }

  private int convertCoordinateToInt(int x, int y) {
    return y * gameMap.width + x;
  }

  private Position convertIntToCoordinate(int pos) {
    return new Position(pos % gameMap.width, pos / gameMap.width);
  }

  private HashSet<Integer> cityTileLocationsForPlayer(Player p) {
    HashSet<Integer> locs = new HashSet<>();
    p.cities.values().forEach(city -> {
      city.citytiles.forEach(cityTile -> {
        locs.add(convertCoordinateToInt(cityTile.pos.x, cityTile.pos.y));
      });
    });
    return locs;
  }

  private HashSet<Integer> newCityPathObstacles(ArrayList<Unit> ignoreUnits) {
    Player opponent = gameState.players[(gameState.id + 1) % 2];

    HashSet<Integer> obstacles = cityTileLocationsForPlayer(player);
    obstacles.addAll(cityTileLocationsForPlayer(opponent));

    player.units.forEach(unit -> {
      int posId = convertCoordinateToInt(unit.pos.x, unit.pos.y);
      if (!ignoreUnits.contains(unit)) {
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

  private HashSet<Integer> currentObstacles(ArrayList<Unit> ignoreUnits) {
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

  private void generateFlowGraphForResourceRouting(ArrayList<Unit> units, ArrayList<Cell> resources) {
    HashSet<Integer> obstacles = currentObstacles(/*ignore these=*/units);
    HashSet<Integer> goodCityTiles = cityTileLocationsForPlayer(player);

    int cellCount = gameMap.width * gameMap.height;
    for (int t = 0; t < timeLayers; t++) {
      for (int x = 0; x < gameMap.width; x++) {
        for (int y = 0; y < gameMap.height; y++) {
          int offset = t * cellCount * 2;
          int cellId = convertCoordinateToInt(x, y);

          if (obstacles.contains(cellId))
            continue;

          int selfCap = goodCityTiles.contains(cellId) ? Integer.MAX_VALUE / 2 : 1;
          flow.add(offset + 2 * cellId, offset + 2 * cellId + 1, selfCap, 0);

          // Add a flow source if there is an available unit there
          if (t == 0) {
            final int X = x, Y = y;
            Optional<Unit> unit = units.stream().filter(u -> u.pos.x == X && u.pos.y == Y).findAny();
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
            int nextPosition = (t == timeLayers - 1) ? tmpId : 2 * tmpId;

            int cost = tmpId == cellId ? 0 : 1;

            // Try to force unit off of city square if it doesn't need to be there
            if (tmpId == cellId && gameMap.getCell(xx, yy).hasCityTile()) {
              String cityid = gameMap.getCell(xx, yy).citytile.cityid;
              Optional<City> city = player.cities.values().stream().filter(c -> c.cityid.equals(cityid)).findAny();

              if (city.isPresent() && city.get().fuel >= city.get().getLightUpkeep() * GameConstants.PARAMETERS.NIGHT_LENGTH) {
                cost = 3;
              }
            }
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
          double d1 = a.pos.distanceTo(gameMap.getCell(X, Y).pos),
                  d2 = b.pos.distanceTo(gameMap.getCell(X, Y).pos);
          return Double.compare(d1, d2);
        });
        int closestDist = Integer.MAX_VALUE;
        if (closestResource.isPresent()) {
          closestDist = (int) closestResource.get().pos.distanceTo(gameMap.getCell(x, y).pos);
        }

        int selfCap = goodCityTiles.contains(cellId) ? Integer.MAX_VALUE / 2 : 1;
        if (closestDist != Integer.MAX_VALUE)
          flow.add(offset + cellId, flow.t, selfCap, Math.max(0, (closestDist - 1) * 50));

        for (int k = 0; k < 5; k++) {
          int xx = x + dx[k], yy = y + dy[k];
          if (xx < 0 || xx >= gameMap.width || yy < 0 || yy >= gameMap.height)
            continue;

          int tmpId = convertCoordinateToInt(xx, yy);
          if (obstacles.contains(tmpId))
            continue;

          int cost = tmpId == cellId ? 0 : 1;
          flow.add(offset + cellId, offset + tmpId, Integer.MAX_VALUE / 2, cost);
        }
      }
    }
  }

  private int followPath(int idx) {
    for (MinCostMaxFlow.Edge e : flow.adj[idx]) {
      if (e.flow > 0) {
        if (e.cost > 0) {
          return e.v2;
        } else {
          return followPath(e.v2);
        }
      }
    }
    return -1;
  }

  private ArrayList<String> readFlowGraphForMoves(Collection<Unit> units) {
    ArrayList<String> actions = new ArrayList<>();
    int cellCount = gameMap.width * gameMap.height;
    for (MinCostMaxFlow.Edge e : flow.adj[flow.s]) {
      if (e.flow > 0 && e.metadata != null) {
        Unit unit = units.stream().filter(u -> u.id == e.metadata).findAny().get();

        int end = followPath(e.v2);
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
    int cellCount = gameMap.width * gameMap.height;
    flow = new MinCostMaxFlow(cellCount * timeLayers * 2 + cellCount);

    generateFlowGraphForResourceRouting(units, resources);

    long[] results = flow.flow();

    return readFlowGraphForMoves(units);
  }

  public String generatePathToNewCityTile(Unit unit, Position cityPosition) {

    HashSet<Integer> obstacles = newCityPathObstacles(new ArrayList<>());

    // store the id of the node we came from
    int[][] path = new int[gameMap.width][gameMap.height];
    for (int i = 0; i < path.length; i++) Arrays.fill(path[i], -1);


    ArrayDeque<Integer> q = new ArrayDeque<>();
    q.add(unit.pos.x);
    q.add(unit.pos.y);

    boolean found = false;
    while (!q.isEmpty()) {
      int x = q.poll(), y = q.poll();

      for (int k = 0; k < 4; k++) {
        int xx = x + dx[k], yy = y + dy[k];
        if (xx < 0 || xx >= gameMap.width || yy < 0 || yy >= gameMap.height)
          continue;
        if (path[xx][yy] != -1)
          continue;
        if (obstacles.contains(convertCoordinateToInt(xx, yy)))
          continue;

        path[xx][yy] = convertCoordinateToInt(x, y);
        q.add(xx);
        q.add(yy);
        if (xx == cityPosition.x && yy == cityPosition.y) {
          found = true;
        }
      }
      if (found) break;
    }

    if (!found) {
      System.err.println(TAG + ": NO ROUTE TO NEW CITY!!! turn=" + gameState.turn);

      Direction dir = unit.pos.directionTo(cityPosition);
      return unit.move(dir);
    }

    int x = cityPosition.x, y = cityPosition.y;
    while (path[x][y] != convertCoordinateToInt(unit.pos.x, unit.pos.y)) {
      Position prevPos = convertIntToCoordinate(path[x][y]);
      x = prevPos.x;
      y = prevPos.y;
    }

    return unit.move(unit.pos.directionTo(new Position(x, y)));
  }

  private boolean checkIfTimedLocationIsTaken(int time, int x, int y, MinCostMaxFlow oldFlow) {
    int idx = time * gameMap.width * gameMap.height * 2 + convertCoordinateToInt(x, y) * 2;
    boolean taken = true;
    for (MinCostMaxFlow.Edge e : oldFlow.adj[idx]) {
      if (e.v2 == e.v1 + 1 && e.cap - e.flow > 0) {
        taken = false;
      }
    }

    return taken;
  }

  private void SetupGraph(boolean canMoveInCity, HashSet<Integer> obstacles, MinCostMaxFlow oldFlow) {

    int cellCount = gameMap.width * gameMap.height;
    flow = new MinCostMaxFlow(cellCount * timeLayers * 2 + cellCount);
    HashSet<Integer> goodCityTiles = cityTileLocationsForPlayer(player);

    for (int t = 0; t < timeLayers; t++) {
      for (int x = 0; x < gameMap.width; x++) {
        for (int y = 0; y < gameMap.height; y++) {
          int offset = t * cellCount * 2;
          int cellId = convertCoordinateToInt(x, y);

          if (obstacles.contains(cellId))
            continue;

          int selfCap = goodCityTiles.contains(cellId) ? Integer.MAX_VALUE / 2 : 1;

          // TODO: Investigate why 't!=0' is required -> likely causing bug
          if (t != 0 && checkIfTimedLocationIsTaken(t, x, y, oldFlow) && !gameMap.getCell(x,y).hasCityTile()) {
            selfCap = 0;
          }
          flow.add(offset + 2 * cellId, offset + 2 * cellId + 1, selfCap, 0);

          // Check all four adjacent squares + remaining still
          // Add an edge if it's a valid square and not an obstacle
          for (int k = 0; k < 5; k++) {
            if (gameMap.getCell(x,y).hasCityTile() && !canMoveInCity && k != 4){
              continue;
            }
            int xx = x + dx[k], yy = y + dy[k];
            if (xx < 0 || xx >= gameMap.width || yy < 0 || yy >= gameMap.height)
              continue;

            int tmpId = convertCoordinateToInt(xx, yy);
            if (obstacles.contains(tmpId))
              continue;

            int nextOffset = (t + 1) * cellCount * 2;
            int nextPosition = (t == timeLayers - 1) ? tmpId : 2 * tmpId;

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

        if (!canMoveInCity && gameMap.getCell(x, y).hasCityTile()) {
          continue;
        }

        if (obstacles.contains(cellId))
          continue;

        for (int k = 0; k < 5; k++) {
          int xx = x + dx[k], yy = y + dy[k];
          if (xx < 0 || xx >= gameMap.width || yy < 0 || yy >= gameMap.height)
            continue;

          int tmpId = convertCoordinateToInt(xx, yy);
          if (obstacles.contains(tmpId))
            continue;

          int cost = tmpId == cellId ? 0 : 1;
          flow.add(offset + cellId, offset + tmpId, Integer.MAX_VALUE / 2, cost);
        }
      }
    }
  }

  private void applySourceAndSinkForCityRouting(String cityId, HashSet<Unit> units) {
    // apply source to unit locations
    for (Unit unit : units) {
      int idx = 2 * convertCoordinateToInt(unit.pos.x, unit.pos.y);
      MinCostMaxFlow.Edge e = flow.add(flow.s, idx, 1, 0);
      e.setMetadata(unit.id);
    }

    // apply sink to destination city
    int offset = timeLayers * gameMap.width * gameMap.height * 2;
    for (City city : player.cities.values()) {
      if (!city.cityid.equals(cityId))
        continue;
      for (CityTile tile : city.citytiles) {
        flow.add(offset + convertCoordinateToInt(tile.pos.x, tile.pos.y), flow.t, Integer.MAX_VALUE / 2, 0);
      }
    }
  }

  private void applySourceAndSinkForColonyRouting(ArrayList<Unit> units, ArrayList<Position> cities) {
    // apply source to unit locations
    for (Unit unit : units) {
      int idx = 2 * convertCoordinateToInt(unit.pos.x, unit.pos.y);
      MinCostMaxFlow.Edge e = flow.add(flow.s, idx, 1, 0);
      e.setMetadata(unit.id);
    }

    // apply sink to new city positions
    int offset = timeLayers * gameMap.width * gameMap.height * 2;
    for (Position p : cities) {
      flow.add(offset+convertCoordinateToInt(p.x,p.y), flow.t, 1, 0);
    }
  }

  public ArrayList<String> generateRoutesToCities(HashMap<Unit, String> assignments, Navigator oldNav) {

    HashSet<String> cities = new HashSet<>();
    cities.addAll(assignments.values());

    ArrayList<String> movements = new ArrayList<>();

    MinCostMaxFlow oldFlow = oldNav.flow;
    for (String cityId : cities) {
      HashSet<Unit> assignedUnits = new HashSet<>();
      for (Unit unit : assignments.keySet()) {
        assignedUnits.add(unit);
      }

      Navigator tmpNav = new Navigator(gameState);
      tmpNav.SetupGraph(false, new HashSet<>(), oldFlow);
      tmpNav.applySourceAndSinkForCityRouting(cityId, assignedUnits);
      long[] results = tmpNav.flow.flow();

      movements.addAll(tmpNav.readFlowGraphForMoves(assignedUnits));

      oldFlow = tmpNav.flow;
    }

    this.flow = oldFlow;

    return movements;
  }


  public ArrayList<String> generateRoutesToColonies(ArrayList<Unit> units, ArrayList<Position> cities,
                                                    Navigator oldNavigator) {
    SetupGraph(false, new HashSet<>(), oldNavigator.flow);
    applySourceAndSinkForColonyRouting(units, cities);

    long[] results = flow.flow();

    return readFlowGraphForMoves(units);
  }
}
