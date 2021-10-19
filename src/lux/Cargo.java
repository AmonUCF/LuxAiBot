package lux;

public class Cargo {
  public int wood;
  public int coal;
  public int uranium;

  public Cargo(int wood, int coal, int uranium) {
    this.wood = wood;
    this.coal = coal;
    this.uranium = uranium;
  }

  public int getFuelValue() {
    return wood + coal * GameConstants.PARAMETERS.RESOURCE_TO_FUEL_RATE.COAL + uranium * GameConstants.PARAMETERS.RESOURCE_TO_FUEL_RATE.URANIUM;
  }
}
