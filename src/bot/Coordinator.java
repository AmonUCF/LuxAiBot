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

  private static int[] dr = {-1, 0, 1, 0}, dc = {0, -1, 0, 1};

  public Coordinator() {}

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

  private ArrayList<String> generateAvailableUnitMovementActions(Navigator navigator, ArrayList<Unit> availableUnits) {
    ArrayList<String> actions = navigator.generateRoutesToResources(availableUnits,
            (ArrayList<Cell>) resourceTiles.stream().filter(cell -> {
              return cell.resource.type.equals(GameConstants.RESOURCE_TYPES.WOOD) ||
                      cell.resource.type.equals(GameConstants.RESOURCE_TYPES.COAL) && player.researchedCoal() ||
                      cell.resource.type.equals(GameConstants.RESOURCE_TYPES.URANIUM) && player.researchedUranium();
            }).collect(Collectors.toList()));

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
        if (resource.resource.type.equals(GameConstants.RESOURCE_TYPES.COAL) && player.researchedCoal())
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
          actions.add(tile.buildWorker());
          workersMade++;
        } else if (!player.researchedUranium()){
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

    Navigator towardResourceNavigator = new Navigator(gameState);
    ArrayList<String> towardResourceMovements =
            generateAvailableUnitMovementActions(towardResourceNavigator, availableUnits);

    Surveyor surveyor = new Surveyor(gameState);
    HashMap<Unit, String> assignments = surveyor.calculateResourceToCityAssignment(fullUnits);

    Navigator towardCitiesNavigator = new Navigator(gameState);
    ArrayList<String> towardCityMovements = towardCitiesNavigator.generateRoutesToCities(assignments,
            towardResourceNavigator);

    // Get all full units which weren't assigned a city
    ArrayList<Unit> possibleColonizers = (ArrayList<Unit>) fullUnits.stream().filter(unit -> {
      return !assignments.containsKey(unit);
    }).collect(Collectors.toList());

    ArrayList<Position> candidateCities = surveyor.findKPotentialCityLocations(Math.min(possibleColonizers.size(), 3));

    ArrayList<String> buildCityActions = new ArrayList<>();
    for(Position p : candidateCities) {
      Optional<Unit> reachedGoal =
              possibleColonizers.stream().filter(unit->p.x==unit.pos.x && p.y==unit.pos.y).findAny();
      if (reachedGoal.isPresent()) {
        possibleColonizers.remove(reachedGoal.get());
        buildCityActions.add(reachedGoal.get().buildCity());
      }
    }
    Navigator colonizerNavigator = new Navigator(gameState);
    ArrayList<String> colonizerActions = colonizerNavigator.generateRoutesToColonies(possibleColonizers,
            candidateCities, towardCitiesNavigator);

    ArrayList<String> cityActions = generateCityActions(gameState);

    // you can add debug annotations using the static methods of the Annotate class.
    // actions.add(Annotate.circle(0, 0));

    /** AI Code Goes Above! **/

    actions.addAll(towardResourceMovements);
//    actions.addAll(awayFromResourceMovements);
    actions.addAll(towardCityMovements);
    actions.addAll(colonizerActions);
    actions.addAll(buildCityActions);

    actions.addAll(cityActions);
    return actions;
  }
}
