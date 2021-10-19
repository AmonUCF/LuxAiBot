package bot;

import lux.*;

import java.util.ArrayDeque;
import java.util.Arrays;

public class Surveyor {


  final private Player player;
  final private GameState gameState;
  final private GameMap gameMap;

  private static int[] dx = {-1, 0, 1, 0}, dy = {0, -1, 0, 1};
  private static int[] diagX = {-1,1,1,-1}, diagY = {-1, -1, 1, 1};

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

  private double[][] applySmallCityIncentive(double[][] score) {

    // TODO: Analyze this ratio and find a more appropriate value
    double smallCityBuffRatio = 1.5;
    player.cities.values().forEach(city->{
      if(city.citytiles.size() == 1) {
        city.citytiles.forEach(cityTile->{
          for(int k=0;k<4;k++){
            int xx = cityTile.pos.x + dx[k], yy = cityTile.pos.y+ dy[k];
            if (xx < 0 || xx >= gameMap.width || yy < 0 || yy >= gameMap.height)
              continue;

            score[xx][yy] *= smallCityBuffRatio;
          }
        });
      }
    });

    // Debuff squares that aren't connected to other city tiles but are very close.
    double antiCityDebuffRatio = 0.4;
    player.cities.values().forEach(city->{
      city.citytiles.forEach(cityTile -> {
        for(int j=0;j<4;j++){
          int xx = cityTile.pos.x + diagX[j], yy = cityTile.pos.y+ diagY[j];
          if (xx < 0 || xx >= gameMap.width || yy < 0 || yy >= gameMap.height)
            continue;

          boolean badTile = true;
          for(int k=0;k<4;k++){
            int xxx = xx + dx[k], yyy = yy+ dy[k];
            if (xxx < 0 || xxx >= gameMap.width || yyy < 0 || yyy >= gameMap.height)
              continue;
            if (gameMap.getCell(xxx,yyy).hasCityTile() && gameMap.getCell(xxx,yyy).citytile.team == player.team) {
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
    return score;
  }

  private int[][] getCityTileDistanceMatrix(boolean includeFullWorker) {
    int[][] dist = new int[gameMap.width][gameMap.height];
    for(int i=0;i<dist.length;i++) Arrays.fill(dist[i], Integer.MAX_VALUE/3);

    ArrayDeque<Integer> q = new ArrayDeque<>();
    player.cities.values().forEach(city -> {
      city.citytiles.forEach(cityTile -> {
        dist[cityTile.pos.x][cityTile.pos.y] = 0;
        q.add(cityTile.pos.x);
        q.add(cityTile.pos.y);
      });
    });

    if(includeFullWorker) {
      player.units.forEach(unit -> {
        if (unit.getCargoSpaceLeft() == 0) {
          dist[unit.pos.x][unit.pos.y] = 0;
          q.add(unit.pos.x);
          q.add(unit.pos.y);
        }
      });
    }

    while(!q.isEmpty()) {
      int x = q.poll(), y = q.poll();
      for(int k=0;k<4;k++){
        int xx = x + dx[k], yy = y + dy[k];
        if (xx < 0 || xx >= gameMap.width || yy < 0 || yy >= gameMap.height)
          continue;
        if(dist[xx][yy] != Integer.MAX_VALUE/3)
          continue;

        // if this is an enemy tile, we can't move here
        if (gameMap.getCell(xx,yy).hasCityTile() && gameMap.getCell(xx,yy).citytile.team != player.team)
          continue;

        dist[xx][yy] = dist[x][y]+1;
        q.add(xx);
        q.add(yy);
      }
    }

    return dist;
  }

  private double[][] applyDistanceDebuff(double[][] score) {

    // TODO: Including full worker here makes us significantly worse - Try again once proper routing has been done
    int[][] dist = getCityTileDistanceMatrix(false);

    double diminishingFactor = .9;
    double[] dimPow = new double[2*gameMap.width+1];
    dimPow[0] = 1;
    for(int i=1;i<dimPow.length;i++)
      dimPow[i] = dimPow[i-1]*diminishingFactor;
    dimPow[dimPow.length-1] = 0;

    for (int x = 0; x < gameMap.width; x++) {
      for (int y = 0; y < gameMap.height; y++) {
        int d = Math.max(Math.min(dist[x][y]-1, gameMap.width*2), 0);
        score[x][y] *= dimPow[d];
      }
    }

    return score;
  }
  private double[][] removeInvalidLocations(double[][] score) {
    for (int x = 0; x < gameMap.width; x++) {
      for (int y = 0; y < gameMap.height; y++) {
        if (gameMap.getCell(x,y).hasResource())
          score[x][y] = 0;
        if(gameMap.getCell(x,y).hasCityTile())
          score[x][y] = 0;
      }
    }
    return score;
  }
  // TODO: apply better calculation for grid tile score
  // Idea: add bonus to locations close to existing city - probably need to cap it to smaller city sizes to promote

  // growth

  public Position findPotentialCityLocation() {
    double[][] score = generateRawScoreMatrix();
    score = applyDistanceDebuff(score);
    score = applySmallCityIncentive(score);
    score = removeInvalidLocations(score);

    Position best = null;
    double bestScore = -1;
    for (int x = 0; x < gameMap.width; x++) {
      for (int y = 0; y < gameMap.height; y++) {
        if(score[x][y] > bestScore) {
          bestScore = score[x][y];
          best = new Position(x,y);
        }
      }
    }

    return best;
  }

}
