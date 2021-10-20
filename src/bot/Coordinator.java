package bot;

import lux.*;

import java.util.*;
import java.util.stream.Collectors;

public class Coordinator {

  final private String TAG = "Coordinator";

  private Player player;
  private GameMap gameMap;

  private HashSet<Unit> colonizers = new HashSet<>();

  private ArrayList<Cell> resourceTiles;

  public Coordinator() {
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

  private ArrayList<String> generateAvailableUnitMovementActions(Navigator navigator, ArrayList<Unit> availableUnits) {
    return navigator.generateRoutesToResources(availableUnits,
        (ArrayList<Cell>) resourceTiles.stream().filter(cell ->
            cell.resource.type.equals(GameConstants.RESOURCE_TYPES.WOOD) ||
                cell.resource.type.equals(GameConstants.RESOURCE_TYPES.COAL) && player.researchedCoal() ||
                cell.resource.type.equals(GameConstants.RESOURCE_TYPES.URANIUM) && player.researchedUranium()
        ).collect(Collectors.toList()));
  }

  // TODO: Most of this logic should be moved to the Surveyor
  private ArrayList<String> generateCityActions() {
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
    ArrayList<City> cities = new ArrayList<>(player.cities.values());
    Collections.sort(cities, (a, b) ->
        -Double.compare(cityScore.get(a), cityScore.get(b))
    );

    int workersMade = 0;
    for (City city : cities) {
      for (CityTile tile : city.citytiles) {
        if (!tile.canAct()) continue;
        if (player.cityTileCount > player.units.size() + workersMade) {
          actions.add(tile.buildWorker());
          workersMade++;
        } else if (!player.researchedUranium()) {
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
    gameMap = gameState.map;

    // get the resource tiles
    getResourceTiles();

    int minCoalForRefuel = 25, minUraniumForRefuel = 10;

    // just gets a list of usable units. assumes all units are workers for now
    ArrayList<Unit> availableUnits =
        (ArrayList<Unit>) player.units
            .stream()
            .filter(unit -> unit.canAct() && unit.getCargoSpaceLeft() > 0 && unit.cargo.uranium < minUraniumForRefuel && unit.cargo.coal < minCoalForRefuel)
            .collect(Collectors.toList());

    ArrayList<Unit> fullUnits =
        (ArrayList<Unit>) player.units
            .stream()
            .filter(unit -> unit.getCargoSpaceLeft() == 0)
            .collect(Collectors.toList());

    /** Units which can act, and have enough resources for a refuel mission. Not previously assigned as colonizer.**/
    ArrayList<Unit> refuelUnits =
        (ArrayList<Unit>) player.units
            .stream()
            .filter(unit ->
                !colonizers.contains(unit) &&
                    (unit.getCargoSpaceLeft() == 0 ||
                        unit.cargo.uranium >= minUraniumForRefuel ||
                        unit.cargo.coal >= minCoalForRefuel)
            )
            .collect(Collectors.toList());

    Navigator towardResourceNavigator = new Navigator(gameState);
    ArrayList<String> towardResourceMovements =
        generateAvailableUnitMovementActions(towardResourceNavigator, availableUnits);

    Surveyor surveyor = new Surveyor(gameState);
    HashMap<Unit, String> assignments = surveyor.calculateResourceToCityAssignment(refuelUnits);

    Navigator towardCitiesNavigator = new Navigator(gameState);
    ArrayList<String> towardCityMovements = towardCitiesNavigator.generateRoutesToCities(assignments,
        towardResourceNavigator);

    /** Get all full units which weren't assigned a city **/
    ArrayList<Unit> possibleColonizers = (ArrayList<Unit>) fullUnits.stream().filter(unit ->
        !assignments.containsKey(unit)
    ).collect(Collectors.toList());

    ArrayList<Position> candidateCities = surveyor.findKPotentialCityLocations(Math.min(possibleColonizers.size(), 5));

    /** First make sure that if a colonizer has already reached goal, we build the city here **/
    ArrayList<String> buildCityActions = new ArrayList<>();
    for (Position p : candidateCities) {
      Optional<Unit> reachedGoal =
          possibleColonizers.stream().filter(unit -> p.x == unit.pos.x && p.y == unit.pos.y).findAny();
      if (reachedGoal.isPresent() && reachedGoal.get().canAct()) {

        // Check that we are either near a resource, or it won't be dark in the next 0-3 days
        boolean okTimeToPlace = false, isLight = true;
        for(char c : "nsew".toCharArray()) {
          Cell adj = gameMap.getCellByPos(p.translate(Direction.getDir(c+""), 1));
          // TODO: should make sure we've researched this resource
          if (adj!=null && (adj.hasResource() || (adj.hasCityTile() && adj.citytile.team == player.team))) {
            okTimeToPlace = true;
          }
        }
        for(int time = gameState.turn; time < gameState.turn+4; time++) {
          isLight = isLight && (time-1) % 40 < 30;
        }
        okTimeToPlace |= isLight;
        if (okTimeToPlace) {
          possibleColonizers.remove(reachedGoal.get());
          buildCityActions.add(reachedGoal.get().buildCity());
        }
      }
    }

    Navigator colonizerNavigator = new Navigator(gameState);
    ArrayList<String> colonizerActions = colonizerNavigator.generateRoutesToColonies(possibleColonizers,
        candidateCities, towardCitiesNavigator);

    Navigator leftoverNavigator = new Navigator(gameState);
    ArrayList<String> tmpActions = new ArrayList<>();
    tmpActions.addAll(towardResourceMovements);
    tmpActions.addAll(towardCityMovements);
    tmpActions.addAll(buildCityActions);
    tmpActions.addAll(colonizerActions);
    ArrayList<Unit> leftovers = findLeftoverUnits(tmpActions);
    ArrayList<String> leftoverUnitMovements = leftoverNavigator.generateRoutesForLeftovers(leftovers,
        colonizerNavigator);

    /**
     * Not super proud of this code. A pretty hacky, stateful, system to ensure colonizers succeed to build
     * something. Could very well cause unstable setups where we let cities go hungry in lieu of building a new one,
     * only to have the new city starve while building the next.
     */
    if (!colonizerActions.isEmpty() || !colonizers.isEmpty()) {
      System.err.println(TAG + " " + gameState.turn + ": Colonizers: " + colonizers + " " + colonizerActions.size() + "\nNew " +
          "Cities: " + candidateCities);
    }

    HashSet<Unit> newColonizers = new HashSet<>();
    for (String unitAction : colonizerActions) {
      String id = unitAction.trim().split(" ")[1];
      Optional<Unit> pUnit = possibleColonizers.stream().filter(unit -> unit.id.equals(id)).findAny();
      pUnit.ifPresent(newColonizers::add);
    }

    for (Unit unit : colonizers) {
      if (!unit.canAct() || unit.getCargoSpaceLeft() != 0) {
        newColonizers.add(unit);
      }
    }

    colonizers = newColonizers;

    ArrayList<String> cityActions = generateCityActions();

    // you can add debug annotations using the static methods of the 'Annotate' class.
    // actions.add(Annotate.circle(0, 0));

    /** AI Code Goes Above! **/

    actions.addAll(towardResourceMovements);
    actions.addAll(towardCityMovements);
    actions.addAll(colonizerActions);
    actions.addAll(leftoverUnitMovements);

    actions = RemoveDuplicateMoveActions(actions);
    actions = RemoveSuicidalMoveActions(gameState, actions);

    actions.addAll(buildCityActions);

    actions.addAll(cityActions);
    return actions;
  }

  private ArrayList<String> RemoveSuicidalMoveActions(GameState gameState, ArrayList<String> actions) {
    boolean isNight = (gameState.turn-1) % 40 >= 30;
    if (!isNight) return actions;

    ArrayList<String> newActions = new ArrayList<>();
    for (String action : actions) {
      if (!action.startsWith("m u_"))
        continue;
      String id = action.trim().split(" ")[1];
      Direction dir = Direction.getDir(action.trim().split(" ")[2]);
      Optional<Unit> oUnit = player.units.stream().filter(u -> u.id.equals(id)).findAny();
      if (!oUnit.isPresent())
        continue;
      Unit unit = oUnit.get();
      if (unit.getCargoSpaceUsed() > 0) {
        newActions.add(action);
        continue;
      }

      Cell newCell = gameMap.getCellByPos(unit.pos.translate(dir, 1));
      if (newCell == null) {
        newActions.add(action);
        continue;
      }

      if (newCell.hasCityTile()) {
        newActions.add(action);
        continue;
      }

      boolean hasResourceNear = false;
      for (char c : "nsewc".toCharArray()) {
        Cell near = gameMap.getCellByPos(newCell.pos.translate(Direction.getDir(c+""), 1));
        if (near != null && near.hasResource()){
          hasResourceNear = true;
        }
      }

      if (hasResourceNear) {
        newActions.add(action);
        continue;
      }
    }
    return newActions;
  }

  private ArrayList<Unit> findLeftoverUnits(ArrayList<String> actions) {
    HashSet<String> unitIds = new HashSet<>();
    actions.stream().forEach(action -> {
      String id = action.trim().split(" ")[1];
      unitIds.add(id);
    });
    return (ArrayList<Unit>) player.units.stream().filter(unit ->
        unit.canAct() && !unitIds.contains(unit.id)).collect(Collectors.toList()
    );
  }

  private ArrayList<String> RemoveDuplicateMoveActions(ArrayList<String> actions) {
    HashSet<Position> positions = new HashSet<>();

    ArrayList<String> newActions = new ArrayList<>();
    for (String action : actions) {
      if (!action.startsWith("m u_")) continue;
      String[] tmp = action.trim().split(" ");
      String unitId = tmp[1];
      Direction dir = Direction.getDir(tmp[2]);

      Unit unit = player.units.stream().filter(u -> u.id.equals(unitId)).findAny().get();
      Position translated = unit.pos.translate(dir, 1);

      if (positions.add(translated)) {
        newActions.add(action);
      }
    }

    if (actions.size() != newActions.size()) {
      System.err.println(TAG + ": REMOVING " + (actions.size() - newActions.size()) + " ACTIONS.");
    }
    return newActions;
  }
}
