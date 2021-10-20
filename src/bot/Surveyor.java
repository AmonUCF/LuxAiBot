package bot;

import lux.*;

import java.util.*;
import java.util.stream.Collectors;

public class Surveyor {

  final private String TAG = "Surveyor";

  final private Player player;
  final private GameState gameState;
  final private GameMap gameMap;

  private static int[] dx = {-1, 0, 1, 0}, dy = {0, -1, 0, 1};
  private static int[] diagX = {-1, 1, 1, -1}, diagY = {-1, -1, 1, 1};

  public Surveyor(GameState gameState) {
    this.gameState = gameState;
    this.gameMap = gameState.map;
    this.player = gameState.players[gameState.id];
  }

  /**
   * TODO: Probably going to need to optimize these values at a later date.
   **/
  private double resourceTypeValue(String resourceType) {
    switch (resourceType) {
      case GameConstants.RESOURCE_TYPES.WOOD:
        return GameConstants.PARAMETERS.WORKER_COLLECTION_RATE.WOOD *
                GameConstants.PARAMETERS.RESOURCE_TO_FUEL_RATE.WOOD;
      case GameConstants.RESOURCE_TYPES.COAL:
        return GameConstants.PARAMETERS.WORKER_COLLECTION_RATE.COAL *
                GameConstants.PARAMETERS.RESOURCE_TO_FUEL_RATE.COAL;
      case GameConstants.RESOURCE_TYPES.URANIUM:
        return GameConstants.PARAMETERS.WORKER_COLLECTION_RATE.URANIUM *
                GameConstants.PARAMETERS.RESOURCE_TO_FUEL_RATE.URANIUM;
      default:
        return 0;
    }
  }

  // TODO: This is currently BoardSize^2, but could be optimized to BoardSize * ResourceTileCount
  private double[][] generateRawScoreMatrix() {
    double[][] score = new double[gameMap.width][gameMap.height];

    double diminishingFactor = 0.75;
    ArrayDeque<Integer> q = new ArrayDeque<>();
    for (int x = 0; x < gameMap.width; x++) {
      for (int y = 0; y < gameMap.height; y++) {
        Cell cell = gameMap.getCell(x, y);
        if (!cell.hasResource())
          continue;

        if (cell.resource.type.equals(GameConstants.RESOURCE_TYPES.COAL) && !player.researchedCoal())
          continue;
        if (cell.resource.type.equals(GameConstants.RESOURCE_TYPES.URANIUM) && !player.researchedUranium())
          continue;

        double[][] tmpScore = new double[gameMap.width][gameMap.height];
        boolean[][] seen = new boolean[gameMap.width][gameMap.height];
        seen[x][y] = true;
        tmpScore[x][y] = cell.resource.amount * resourceTypeValue(cell.resource.type);

        q.add(x);
        q.add(y);

        while (!q.isEmpty()) {
          int xx = q.poll();
          int yy = q.poll();

          for (int k = 0; k < 4; k++) {
            int xxx = xx + dx[k], yyy = yy + dy[k];
            if (xxx < 0 || xxx >= gameMap.width || yyy < 0 || yyy >= gameMap.height)
              continue;
            if (seen[xxx][yyy])
              continue;

            seen[xxx][yyy] = true;
            tmpScore[xxx][yyy] += diminishingFactor * tmpScore[xx][yy];

            q.add(xxx);
            q.add(yyy);
          }
        }

        for (int i = 0; i < gameMap.width; i++) {
          for (int j = 0; j < gameMap.height; j++) {
            score[i][j] += tmpScore[i][j];
          }
        }
      }
    }

    return score;
  }

  private void applySmallCityIncentive(double[][] score) {
    // TODO: Analyze this ratio and find a more appropriate value
    double smallCityBuffRatio = 1.5;
    player.cities.values().forEach(city -> {
      if (city.citytiles.size() == 1) {
        city.citytiles.forEach(cityTile -> {
          for (int k = 0; k < 4; k++) {
            int xx = cityTile.pos.x + dx[k], yy = cityTile.pos.y + dy[k];
            if (xx < 0 || xx >= gameMap.width || yy < 0 || yy >= gameMap.height)
              continue;

            score[xx][yy] *= smallCityBuffRatio;
          }
        });
      }
    });

    // Debuff squares that aren't connected to other city tiles but are very close.
    double antiCityDebuffRatio = 0.4;
    player.cities.values().forEach(city -> {
      city.citytiles.forEach(cityTile -> {
        for (int j = 0; j < 4; j++) {
          int xx = cityTile.pos.x + diagX[j], yy = cityTile.pos.y + diagY[j];
          if (xx < 0 || xx >= gameMap.width || yy < 0 || yy >= gameMap.height)
            continue;

          boolean badTile = true;
          for (int k = 0; k < 4; k++) {
            int xxx = xx + dx[k], yyy = yy + dy[k];
            if (xxx < 0 || xxx >= gameMap.width || yyy < 0 || yyy >= gameMap.height)
              continue;
            if (gameMap.getCell(xxx, yyy).hasCityTile() && gameMap.getCell(xxx, yyy).citytile.team == player.team) {
              badTile = false;
              break;
            }
          }
          if (badTile) {
            score[xx][yy] *= antiCityDebuffRatio;
          }
        }
      });
    });
  }

  private int[][] getCityTileDistanceMatrix(boolean includeFullWorker) {
    int[][] dist = new int[gameMap.width][gameMap.height];
    for (int i = 0; i < dist.length; i++) Arrays.fill(dist[i], Integer.MAX_VALUE / 3);

    ArrayDeque<Integer> q = new ArrayDeque<>();
    player.cities.values().forEach(city -> {
      city.citytiles.forEach(cityTile -> {
        dist[cityTile.pos.x][cityTile.pos.y] = 0;
        q.add(cityTile.pos.x);
        q.add(cityTile.pos.y);
      });
    });

    if (includeFullWorker) {
      player.units.forEach(unit -> {
        if (unit.getCargoSpaceLeft() == 0) {
          dist[unit.pos.x][unit.pos.y] = 0;
          q.add(unit.pos.x);
          q.add(unit.pos.y);
        }
      });
    }

    while (!q.isEmpty()) {
      int x = q.poll(), y = q.poll();
      for (int k = 0; k < 4; k++) {
        int xx = x + dx[k], yy = y + dy[k];
        if (xx < 0 || xx >= gameMap.width || yy < 0 || yy >= gameMap.height)
          continue;
        if (dist[xx][yy] != Integer.MAX_VALUE / 3)
          continue;

        // if this is an enemy tile, we can't move here
        if (gameMap.getCell(xx, yy).hasCityTile() && gameMap.getCell(xx, yy).citytile.team != player.team)
          continue;

        dist[xx][yy] = dist[x][y] + 1;
        q.add(xx);
        q.add(yy);
      }
    }

    return dist;
  }

  private void applyDistanceDebuff(double[][] score) {
    // TODO: Including full worker here makes us significantly worse - Try again once proper routing has been done
    int[][] dist = getCityTileDistanceMatrix(false);

    double diminishingFactor = .9;
    double[] dimPow = new double[2 * gameMap.width + 1];
    dimPow[0] = 1;
    for (int i = 1; i < dimPow.length; i++)
      dimPow[i] = dimPow[i - 1] * diminishingFactor;
    dimPow[dimPow.length - 1] = 0;

    for (int x = 0; x < gameMap.width; x++) {
      for (int y = 0; y < gameMap.height; y++) {
        int d = Math.max(Math.min(dist[x][y] - 1, gameMap.width * 2), 0);
        score[x][y] *= dimPow[d];
      }
    }
  }

  private void removeInvalidLocations(double[][] score) {
    for (int x = 0; x < gameMap.width; x++) {
      for (int y = 0; y < gameMap.height; y++) {
        if (gameMap.getCell(x, y).hasResource())
          score[x][y] = 0;
        if (gameMap.getCell(x, y).hasCityTile())
          score[x][y] = 0;
      }
    }
  }
  // TODO: apply better calculation for grid tile score
  // Idea: add bonus to locations close to existing city - probably need to cap it to smaller city sizes to promote

  // growth

  public Position findPotentialCityLocation() {
    return findKPotentialCityLocations(1).get(0);
  }

  public ArrayList<Position> findKPotentialCityLocations(int K) {
    final double[][] score = generateRawScoreMatrix();
    applyDistanceDebuff(score);
    applySmallCityIncentive(score);
    removeInvalidLocations(score);

    TreeSet<Position> best = new TreeSet<>((a, b) -> {
      return -Double.compare(score[a.x][a.y], score[b.x][b.y]);
    });

    for (int x = 0; x < gameMap.width; x++) {
      for (int y = 0; y < gameMap.height; y++) {
        if (best.size() < K) best.add(new Position(x, y));
        else {
          best.add(new Position(x, y));
          best.remove(best.last());
        }
      }
    }

    return (ArrayList<Position>) best.stream().collect(Collectors.toList());
  }

  private int convertCoordinateToInt(int x, int y) {
    return y * gameMap.width + x;
  }

  private Position convertIntToCoordinate(int pos) {
    return new Position(pos % gameMap.width, pos / gameMap.width);
  }

  // Calculates the cost of supply for the next 20 night turns
  private int estimatedNecessaryFuel(City city) {
    // Forces player to expand to two tiles quickly
    if (player.cities.values().size() == 1 && city.citytiles.size() == 1) {
      return 0;
    }
    int upkeep = (int) city.getLightUpkeep();
    int cycleLength = GameConstants.PARAMETERS.DAY_LENGTH + GameConstants.PARAMETERS.NIGHT_LENGTH;
    int turnsLeft = GameConstants.PARAMETERS.MAX_DAYS - gameState.turn;
    int nightTurnsLeft = (turnsLeft / cycleLength * GameConstants.PARAMETERS.NIGHT_LENGTH) +
            Math.min(GameConstants.PARAMETERS.NIGHT_LENGTH, turnsLeft % cycleLength);
    return upkeep * Math.min(nightTurnsLeft, 10);
  }

  private static class UnitCityAssignment implements Comparable<UnitCityAssignment> {
    Unit unit;
    String cityId;
    int fuelValue;

    public UnitCityAssignment(Unit unit, String cityId, int fuelValue) {
      this.unit = unit;
      this.cityId = cityId;
      this.fuelValue = fuelValue;
    }

    @Override
    public int compareTo(UnitCityAssignment o) {
      return fuelValue - o.fuelValue;
    }
  }

  private void followFlowAndRecordFuelAssignment(int idx, int fuel, MinCostMaxFlow flow,
                                                 HashMap<String, Integer> assignedFuel) {
    for (MinCostMaxFlow.Edge e : flow.adj[idx]) {
      if (e.flow <= 0) continue;
      if (e.v2 >= gameMap.width * gameMap.height) {
        Position p = convertIntToCoordinate(e.v1);
        Cell cell = gameMap.getCell(p.x, p.y);
        if (!cell.hasCityTile()) {
          System.err.println(TAG + ": SHIIIIIIIIIIIIIIIIIIIIIIT " + (gameMap.width * gameMap.height) + " edge=" + e.toString());
          continue;
        }

        int amountFuelAssigned = Math.min(fuel, e.flow);
        e.flow -= amountFuelAssigned;

        String cityId = cell.citytile.cityid;
        if (assignedFuel.containsKey(cityId)) {
          assignedFuel.put(cityId, assignedFuel.get(cityId) + amountFuelAssigned);
        } else {
          assignedFuel.put(cityId, amountFuelAssigned);
        }
      } else {
        int newFlow = Math.min(fuel, e.flow);
        e.flow -= newFlow;
        followFlowAndRecordFuelAssignment(e.v2, newFlow, flow, assignedFuel);
      }
    }
  }

  private UnitCityAssignment readGraphAndFindBestAssignment(MinCostMaxFlow flow, ArrayList<Unit> units,
                                                            Set<Unit> alreadyAssignedUnits) {
    ArrayList<UnitCityAssignment> list = new ArrayList<>();

    // TODO: I think we should go in order of highest fuel cargo amount
    for (Unit unit : units) {
      if (alreadyAssignedUnits.contains(unit)) continue;

      HashMap<String, Integer> assignedFuel = new HashMap<>();
      followFlowAndRecordFuelAssignment(convertCoordinateToInt(unit.pos.x, unit.pos.y), unit.cargo.getFuelValue(),
              flow, assignedFuel);

      for (String s : assignedFuel.keySet()) {
        list.add(new UnitCityAssignment(unit, s, assignedFuel.get(s)));
      }
    }
    if (list.isEmpty()) {
      return null;
    }
    Collections.sort(list);

    return list.get(list.size() - 1);
  }

  private boolean getSingleAssignment(HashMap<Unit, String> assignments, ArrayList<Unit> units) {
    int cellCount = gameMap.width * gameMap.height;
    int cityCount = player.cities.size();

    int idx = 0;
    HashMap<String, Integer> cityIndex = new HashMap<>();
    for (City c : player.cities.values()) {
      cityIndex.put(c.cityid, idx++);
    }

    MinCostMaxFlow flow = new MinCostMaxFlow(cellCount + cityCount);

    // Add source edges
    for (Unit unit : units) {
      if (assignments.containsKey(unit))
        continue;
      flow.add(flow.s, convertCoordinateToInt(unit.pos.x, unit.pos.y), unit.cargo.getFuelValue(), 0);
    }

    // Add intermediate edges
    for (int x = 0; x < gameMap.width; x++) {
      for (int y = 0; y < gameMap.height; y++) {
        Cell cell = gameMap.getCell(x, y);
        int cellId = convertCoordinateToInt(x, y);

        // Don't route through enemy controlled squares
        if (cell.hasCityTile() && cell.citytile.team != player.team)
          continue;

        // only route directly to the City node
        if (cell.hasCityTile()) {
          flow.add(cellId, cellCount + cityIndex.get(cell.citytile.cityid), Integer.MAX_VALUE / 2, 0);
          continue;
        }

        for (int k = 0; k < 4; k++) {
          int xx = x + dx[k], yy = y + dy[k];
          if (xx < 0 || xx >= gameMap.width || yy < 0 || yy >= gameMap.height)
            continue;

          int tmpId = convertCoordinateToInt(xx, yy);
          flow.add(cellId, tmpId, Integer.MAX_VALUE / 2, 1);
        }
      }
    }

    // Add sink edges
    for (City city : player.cities.values()) {
      int estimate = estimatedNecessaryFuel(city);
      int existingFuel = (int) city.fuel;
      for (Unit u : assignments.keySet()) {
        if (assignments.get(u).equals(city.cityid)) {
          existingFuel += u.cargo.getFuelValue();
        }
      }

      flow.add(cellCount + cityIndex.get(city.cityid), flow.t, (int) Math.max(estimate - existingFuel, 0), 0);
    }

    long[] results = flow.flow();

    UnitCityAssignment assignment = readGraphAndFindBestAssignment(flow, units, assignments.keySet());
    if (assignment != null) {
      assignments.put(assignment.unit, assignment.cityId);
      return true;
    }

    return false;
  }

  public HashMap<Unit, String> calculateResourceToCityAssignment(ArrayList<Unit> fullUnits) {

    HashMap<Unit, String> assignments = new HashMap<>();
    if (fullUnits.isEmpty()) {
      return assignments;
    }
    int count = 0;
    for (Unit u : fullUnits) {
      if (!getSingleAssignment(assignments, fullUnits))
        break;
    }

//    // Go ahead and send uranium to cities
//    HashMap<City, Integer> extraFuel = new HashMap<>();
//    for (Unit u : fullUnits) {
//      if (assignments.containsKey(u)) continue;
//      if (u.cargo.uranium == 0) continue;
//
//      double leastTurns = 100000;
//      City best = null;
//      for (City c : player.cities.values()) {
//        int extra = (extraFuel.containsKey(c)) ? extraFuel.get(c) : 0;
//        double turnsTilDead = (c.fuel + extra) / c.getLightUpkeep();
//        if (turnsTilDead < leastTurns) {
//          leastTurns = turnsTilDead;
//          best = c;
//        }
//      }
//
//      if (best != null) {
//        if (extraFuel.containsKey(best)) {
//          extraFuel.put(best,
//                  extraFuel.get(best) + u.cargo.uranium * GameConstants.PARAMETERS.RESOURCE_TO_FUEL_RATE.URANIUM);
//        } else {
//          extraFuel.put(best, u.cargo.uranium * GameConstants.PARAMETERS.RESOURCE_TO_FUEL_RATE.URANIUM);
//        }
//        System.err.println(TAG+": ASSIGNING URANIUM WORKER="+u.id +" to city="+best.cityid);
//        assignments.put(u, best.cityid);
//      }
//    }

    String ass = "";
    for (Unit u : assignments.keySet())
      ass += "(" + u + "," + assignments.get(u) + ")";

    if (ass.length() != 0) {
      System.err.println(TAG + " "+gameState.turn+": ASSIGNMENTS=" + ass);
    }

    return assignments;
  }
}
