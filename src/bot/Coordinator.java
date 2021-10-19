package bot;

import lux.*;

import java.sql.Array;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class Coordinator {

  final private String TAG = "Coordinator";

  private Player player, opponent;
  private GameMap gameMap;

  private ArrayList<Cell> resourceTiles;

  private HashMap<Unit, Position> colonizers;

  private static int[] dr = {-1, 0, 1, 0}, dc = {0, -1, 0, 1};

  public Coordinator() {
    colonizers = new HashMap<>();
  }

  /**
   * Grabs all tiles which are resource squares and stores them in class var
   **/
  private void getResourceTiles() {
    resourceTiles = new ArrayList<>();
    for (int y = 0; y < gameMap.height; y++) {
      for (int x = 0; x < gameMap.width; x++) {
        Cell cell = gameMap.getCell(x, y);
        if (cell.hasResource()) {
          resourceTiles.add(cell);
        }
      }
    }
  }

  private void checkIfColonizersAreValid() {
    HashSet<Unit> removeSet = new HashSet<>();
    for (Unit unit : colonizers.keySet()) {
      Position colonyPos = colonizers.get(unit);

      if (gameMap.getCell(colonyPos.x, colonyPos.y).hasCityTile()) {
        removeSet.add(unit);
      }
    }

    removeSet.forEach(unit -> colonizers.remove(unit));
  }

  private boolean shouldBuildNewCityTile(GameState gameState) {
    return colonizers.size() < 1;
  }

  private ArrayList<String> generateAvailableUnitMovementActions(GameState gameState, ArrayList<Unit> availableUnits) {
    Navigator navigator = new Navigator(gameState);
    ArrayList<String> actions = navigator.generateRoutesToResources(availableUnits,
            (ArrayList<Cell>) resourceTiles.stream().filter(cell -> {
              return cell.resource.type.equals(GameConstants.RESOURCE_TYPES.WOOD) ||
                      cell.resource.type.equals(GameConstants.RESOURCE_TYPES.COAL) && player.researchedCoal() ||
                      cell.resource.type.equals(GameConstants.RESOURCE_TYPES.URANIUM) && player.researchedUranium();
            }).collect(Collectors.toList()));

    return actions;
  }

  private ArrayList<String> generateFullUnitMovementActions(GameState gameState, ArrayList<Unit> units) {
    ArrayList<String> actions = new ArrayList<>();

    // Get candidate city location, then find the closest unit and assign it to build the tile.
    if (!units.isEmpty() && shouldBuildNewCityTile(gameState) && colonizers.isEmpty()) {
      Surveyor surveyor = new Surveyor(gameState);
      Position newCity = surveyor.findPotentialCityLocation();

      Optional<Unit> closestUnit = units.stream().min((a, b) -> {
        int d1 = (int) a.pos.distanceTo(newCity);
        int d2 = (int) b.pos.distanceTo(newCity);
        return d1 - d2;
      });

      System.err.println(TAG + ": FOUND COLONIZER " + newCity.toString());
      colonizers.put(closestUnit.get(), newCity);
    }

    for (Unit unit : units) {
      if (colonizers.containsKey(unit)) {
        Position colonyPosition = colonizers.get(unit);
        if (colonyPosition.equals(unit.pos)) {
          colonizers.remove(unit);
          actions.add(unit.buildCity());
          System.err.println(TAG + ": trying to build city tile -> " + colonyPosition.x + " " + colonyPosition.y);
        } else {
          Navigator navigator = new Navigator(gameState);
          actions.add(navigator.generatePathToNewCityTile(unit, colonyPosition));
        }

        continue;
      }
      if (player.cities.size() > 0) {
        double closestDist = 999999;
        CityTile closestCityTile = null;
        for (City city : player.cities.values()) {
          for (CityTile citytile : city.citytiles) {
            double dist = citytile.pos.distanceTo(unit.pos);
            if (dist < closestDist) {
              closestCityTile = citytile;
              closestDist = dist;
            }
          }
        }
        if (closestCityTile != null) {
          Direction dir = unit.pos.directionTo(closestCityTile.pos);
          actions.add(unit.move(dir));
        }
      }
    }
    return actions;
  }

  // TODO: Most of this logic should be moved to the Surveyor
  private ArrayList<String> generateCityActions(GameState gameState) {
    ArrayList<String> actions = new ArrayList<>();

    /**
     * Calculate a score based on how close resources are to the given city. Cities without many resources nearby are
     * bad places to spawn a worker.
     **/
    HashMap<City, Double> cityScore = new HashMap<>();
    for (City city : player.cities.values()) {
      double score = 0;
      for (Cell resource : resourceTiles) {
        int minDist = Integer.MAX_VALUE;
        for (CityTile tile : city.citytiles) {
          minDist = Math.min(minDist, (int) tile.pos.distanceTo(resource.pos));
        }

        // calculates potential fuel available from the current resource
        int fuel = resource.resource.amount;
        if(resource.resource.type.equals(GameConstants.RESOURCE_TYPES.COAL) && player.researchedCoal())
          fuel *= GameConstants.PARAMETERS.RESOURCE_TO_FUEL_RATE.COAL;
        if (resource.resource.type.equals(GameConstants.RESOURCE_TYPES.URANIUM) && player.researchedUranium())
          fuel *= GameConstants.PARAMETERS.RESOURCE_TO_FUEL_RATE.URANIUM;

        // Reduce potential fuel-gain based on distance
        score += fuel * Math.pow(.9, minDist);
      }

      cityScore.put(city, score);
    }
    ArrayList<City> cities = new ArrayList<>();
    cities.addAll(player.cities.values());
    Collections.sort(cities, (a, b) -> {
      return Double.compare(cityScore.get(a), cityScore.get(b));
    });

    int workersMade = 0;
    for (City city : cities) {
      for (CityTile tile : city.citytiles) {
        if (!tile.canAct()) continue;
        if (player.cityTileCount + workersMade > player.units.size()) {
          System.err.println("trying to build worker -> " + player.cityTileCount + " " + player.units.size());
          actions.add(tile.buildWorker());
          workersMade++;
        } else {
          actions.add(tile.research());
        }
      }
    }

    return actions;
  }

  public ArrayList<String> generateTurnActions(GameState gameState) {

    ArrayList<String> actions = new ArrayList<>();

    // store some important game state variables
    player = gameState.players[gameState.id];
    opponent = gameState.players[(gameState.id + 1) % 2];
    gameMap = gameState.map;

    // get the resource tiles
    getResourceTiles();

    checkIfColonizersAreValid();

    // just gets a list of usable units. assumes all units are workers for now
    ArrayList<Unit> availableUnits =
            (ArrayList<Unit>) player.units
                    .stream()
                    .filter(unit -> unit.canAct() && unit.getCargoSpaceLeft() > 0)
                    .collect(Collectors.toList());

    ArrayList<Unit> fullUnits =
            (ArrayList<Unit>) player.units
                    .stream()
                    .filter(unit -> unit.canAct() && unit.getCargoSpaceLeft() == 0)
                    .collect(Collectors.toList());

    ArrayList<String> towardResourceMovements = generateAvailableUnitMovementActions(gameState, availableUnits);

    ArrayList<String> awayFromResourceMovements = generateFullUnitMovementActions(gameState, fullUnits);

    ArrayList<String> cityActions = generateCityActions(gameState);

    // you can add debug annotations using the static methods of the Annotate class.
    // actions.add(Annotate.circle(0, 0));

    /** AI Code Goes Above! **/

    actions.addAll(towardResourceMovements);
    actions.addAll(awayFromResourceMovements);
    actions.addAll(cityActions);
    return actions;
  }
}
