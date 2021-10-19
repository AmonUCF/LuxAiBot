package bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;

public class MinCostMaxFlow{
  ArrayList<Edge>[] adj;
  ArrayDeque<Integer> q;
  int n, s, t;
  long[] dist, min;
  Edge[] path;
  final long oo = (long)1e18;
  public static class Edge {
    String metadata;
    int v1, v2, cap, flow;
    long cost;
    Edge rev;
    public Edge (int v1, int v2, int cap, long cost) {
      this.v1=v1;this.v2=v2;this.cap=cap;this.cost=cost;
    }
    public void setMetadata(String s) {
      metadata = s;
    }
    public String toString() {
      return "("+v1+","+v2+","+flow+","+cost+")";
    }
  }
  public MinCostMaxFlow(int N) {
    n = N; s=n++;t=n++;
    adj = new ArrayList[n];
    for(int i=0;i<n;i++)adj[i] = new ArrayList<>();
    dist = new long[n];
    min = new long[n];
    path = new Edge[n];
    q = new ArrayDeque<>();
  }
  public Edge add(int v1, int v2, int cap, long cost){
    Edge e = new Edge(v1,v2,cap,cost);
    Edge rev = new Edge(v2,v1,0,-cost);
    adj[v1].add(rev.rev=e);
    adj[v2].add(e.rev=rev);
    return e;
  }
  private boolean spfa() {
    Arrays.fill(dist, oo);
    path[t] = null;
    dist[s] = 0;
    min[s] = oo;
    q.add(s);
    while(!q.isEmpty()){
      int node = q.poll();
      for(Edge e : adj[node])
        if(e.cap>e.flow&&dist[e.v2]>dist[e.v1]+e.cost){
          dist[e.v2]= dist[e.v1]+e.cost;
          path[e.v2]=e;
          min[e.v2] = Math.min(e.cap-e.flow, min[node]);
          q.add(e.v2);
        }
    }
    return dist[t]==oo?false:true;
  }
  public long[] flow() {
    long cost = 0, flow = 0;
    while(spfa()){
      for(int i = t; path[i]!=null ; i=path[i].v1){
        path[i].flow+=min[t];
        path[i].rev.flow=-path[i].flow;
        cost+=path[i].cost*min[t];
      }
      flow+=min[t];
    }
    return new long[]{cost,flow};
  }
}