import java.util.ArrayList;

import bot.Coordinator;
import lux.*;

public class Bot {
  public static void main(final String[] args) throws Exception {
    Agent agent = new Agent();
    Coordinator coordinator = new Coordinator();
    // initialize
    agent.initialize();
    while (true) {
      /** Do not edit! **/
      // wait for updates
      agent.update();

      GameState gameState = agent.gameState;

      ArrayList<String> actions  = coordinator.generateTurnActions(gameState);

      /** Do not edit! **/
      StringBuilder commandBuilder = new StringBuilder("");
      for (int i = 0; i < actions.size(); i++) {
        if (i != 0) {
          commandBuilder.append(",");
        }
        commandBuilder.append(actions.get(i));
      }
      System.out.println(commandBuilder.toString());
      // end turn
      agent.endTurn();

    }
  }
}
