/*
 * VDRPMDPC.java - ACO vs ABC, dataset Semi-Urban 20/40/60/100.
 * Satu file Java + dua CSV untuk instans yang sedang diuji.
 *
 * UBAH CUKUP SATU BARIS DATASET DI BAWAH:
 *   "Semi-Urban_20" -> "Semi-Urban_40" / "Semi-Urban_60" / "Semi-Urban_100"
 *
 * CSV: Van_Semi-Urban_XX.csv dan Drone_Semi-Urban_XX.csv
 * Harus berada di folder yang sama dengan file Java (atau subfolder Data).
 * Jalankan: java VDRPMDPC.java
 * Pilot:    java VDRPMDPC.java --pilot
 * Baterai: java VDRPMDPC.java --battery 1680
 *
 * Eksperimen: populasi 20/50/100, iterasi 100, 10 seed (1..10).
 * Objective: waktu penyelesaian pengiriman (detik), fitness=1/objective.
 * Output: hasil 60 run, tabel, dan rute di terminal serta TXT dan CSV di folder proyek.
 *
 * ASUMSI MODEL (bukan reproduksi persis model jurnal):
 * K=1 van, M=1 drone, beberapa sortie berurutan; 1 pelanggan per sortie.
 * Kecepatan van/drone 40/60 km/jam (asumsi implementasi sebelumnya).
 * Persiapan drone dapat overlap dengan pelayanan van, tanpa time window.
 */
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class VDRPMDPC {
    static final Locale LOCALE = Locale.US;
    // ================== GANTI DATASET DI SINI SAJA ==================
    static final String DATASET = "Semi-Urban_100";
    // Pilihan: "Semi-Urban_20", "Semi-Urban_40", "Semi-Urban_60", "Semi-Urban_100"
    // ================================================================
    static final int[] POPULATIONS = {20, 50, 100};
    static final int[] DEMAND_20 = {0,3,4,3,3,1,3,5,5,19,3,6,16,6,8,4,12,3,9,5,5};
    static final int[] DEMAND_40 = {0,3,4,3,3,1,3,5,5,19,3,6,16,6,8,4,12,3,9,5,5,6,4,4,2,2,3,2,4,2,4,1,5,4,12,5,4,4,3,6,3};
    static final int[] DEMAND_60 = {0,4,4,2,8,5,5,4,1,5,3,20,1,2,3,8,3,1,5,11,1,6,5,3,5,4,5,6,10,3,15,4,6,6,6,3,5,1,6,5,1,6,11,18,4,3,1,10,10,7,6,2,6,6,2,2,2,6,6,3,2};
    static final int[] DEMAND_100 = {0,3,4,3,3,1,3,5,5,19,3,6,16,6,8,4,12,3,9,5,5,6,4,4,2,2,3,2,4,2,4,1,5,4,12,5,4,4,3,6,3,4,4,2,8,5,5,4,1,5,3,20,1,2,3,8,3,1,5,11,1,6,5,3,5,4,5,6,10,3,15,4,6,6,6,3,5,1,6,5,1,6,11,18,4,3,1,10,10,7,6,2,6,6,2,2,2,6,6,3,2};
    static final int[] DEMAND = chooseDemand(DATASET);
    static final int N = DEMAND.length - 1, POINTS = N + 1;

    static int[] chooseDemand(String dataset) {
        return switch (dataset) {
            case "Semi-Urban_20" -> DEMAND_20;
            case "Semi-Urban_40" -> DEMAND_40;
            case "Semi-Urban_60" -> DEMAND_60;
            case "Semi-Urban_100" -> DEMAND_100;
            default -> throw new IllegalArgumentException("Dataset tidak dikenal: " + dataset);
        };
    }
    static final double VAN_KMH = 40.0, DRONE_KMH = 60.0;
    static final double VAN_CAPACITY = 705.0, DRONE_CAPACITY = 6.0;
    static final double VAN_SERVICE = 300.0, DRONE_SERVICE = 30.0;
    static final double PREPARATION = 300.0, LAUNCH = 20.0, LANDING = 33.33;
    static final double EPS = 1e-9;
    static double[][] VAN;
    static double[][] DRONE;

    static double battery = 1080.0;
    static int maxIterations = 100, runs = 10;
    static String outputSuffix = "_" + N;

    public static void main(String[] args) throws Exception {
        for (int i=0; i<args.length; i++) {
            switch (args[i]) {
                case "--pilot" -> { maxIterations = 5; runs = 2; outputSuffix = "_" + N + "_pilot"; }
                case "--battery" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("Nilai --battery belum diisi");
                    battery = Double.parseDouble(args[i]);
                }
                default -> throw new IllegalArgumentException("Argumen tidak dikenal: " + args[i]);
            }
        }
        if (battery != 1080.0 && battery != 1680.0)
            throw new IllegalArgumentException("Pilih --battery 1080 atau --battery 1680");
        VAN = readDistanceMatrix("Van_" + DATASET + ".csv");
        DRONE = readDistanceMatrix("Drone_" + DATASET + ".csv");
        validateData();
        int totalDemand = 0, eligible = 0;
        for (int i=1; i<=N; i++) {
            totalDemand += DEMAND[i];
            if (DEMAND[i] <= DRONE_CAPACITY) eligible++;
        }
        StringBuilder fullLog = new StringBuilder();
        String headerText = String.format(LOCALE,
                "PENGUJIAN ACO vs ABC | VDRPMDPC %s%n" +
                "=============================================================%n" +
                "Pelanggan: %d | Matriks: %dx%d | Total demand: %d kg%n" +
                "Layak kapasitas drone: %d/%d (%.2f%%), belum tentu layak baterai%n" +
                "K=1, M=1 (sortie berurutan) | Baterai: %.0f s%n" +
                "Kecepatan van/drone: %.0f/%.0f km/jam [ASUMSI kode asal]%n" +
                "Populasi: 20, 50, 100 | Iterasi: %d | Run: %d%n" +
                "Fitness=1/Objective; pemenang berdasarkan mean objective TERKECIL.%n",
                DATASET, N, POINTS, POINTS, totalDemand, eligible, N, 100.0*eligible/N,
                battery, VAN_KMH, DRONE_KMH, maxIterations, runs);
        fullLog.append(headerText);
        System.out.print(headerText);
        if (maxIterations != 100 || runs != 10) {
            fullLog.append("MODE PILOT: bukan hasil pengujian utama!\n");
            System.out.println("MODE PILOT: bukan hasil pengujian utama!");
        }

        ArrayList<RunRecord> records = new ArrayList<>();
        ArrayList<String> convergence = new ArrayList<>();
        convergence.add("instans,algoritma,populasi,run,iterasi,best_objective_detik");
        for (int population : POPULATIONS) {
            for (int seed=1; seed<=runs; seed++) {
                for (String algorithm : new String[]{"ACO", "ABC"}) {
                    Search search = algorithm.equals("ACO")
                            ? new ACO(population, maxIterations, seed)
                            : new ABC(population, maxIterations, seed);
                    long start = System.nanoTime();
                    Candidate best = search.optimize();
                    double elapsed = (System.nanoTime()-start) / 1_000_000_000.0;
                    Eval result = best.evaluation;
                    if (!result.feasible || !Double.isFinite(result.objective) || result.objective <= 0)
                        throw new IllegalStateException("Tidak ada solusi feasible: " + algorithm);
                    records.add(new RunRecord(algorithm, population, seed, result.objective,
                            1.0 / result.objective, elapsed, search.evaluations(), result));
                    if (algorithm.equals("ACO") && seed == 1) {
                        String section = String.format(LOCALE, "%n--- POPULASI %d ---%n", population);
                        fullLog.append(section);
                        System.out.print(section);
                    }
                    String runLine = String.format(LOCALE,
                            "%s | pop=%3d | run=%2d | obj=%11.2f s | fitness=%.10f | komputasi=%8.4f s%n",
                            algorithm, population, seed, result.objective, 1.0/result.objective, elapsed);
                    fullLog.append(runLine);
                    System.out.print(runLine);
                    double[] history = search.history();
                    for (int iteration=0; iteration<history.length; iteration++)
                        convergence.add(String.format(LOCALE,"%s,%s,%d,%d,%d,%.8f",
                                DATASET,algorithm,population,seed,iteration+1,history[iteration]));
                }
            }
        }
        writeRaw(records);
        writeSummary(records);
        writeBestConfiguration(records);
        Files.write(outputPath("konvergensi"+outputSuffix+".csv"), convergence, StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            printSummary(records, capture);
            printBestRoutes(records, capture);
            printWinnerSummary(records, capture);
        }
        String summaryText = buffer.toString(StandardCharsets.UTF_8);
        System.out.print(summaryText);
        fullLog.append(summaryText);
        String footer = String.format(LOCALE,
                "%nCSV disimpan di folder proyek: hasil_run%s.csv, rekap%s.csv, tabel2_terbaik%s.csv, konvergensi%s.csv%n" +
                "Laporan TXT: PENGUJIAN ACO vs ABC%s.txt%n" +
                "Catatan: waktu komputasi bergantung pada spesifikasi komputer dan beban sistem.%n",
                outputSuffix, outputSuffix, outputSuffix, outputSuffix, outputSuffix);
        fullLog.append(footer);
        System.out.print(footer);
        Path logPath = outputPath("PENGUJIAN ACO vs ABC" + outputSuffix + ".txt");
        Files.writeString(logPath, fullLog.toString(), StandardCharsets.UTF_8);
    }

    static double[][] readDistanceMatrix(String fileName) throws IOException {
        Path path = Path.of(fileName);
        if (!Files.exists(path)) {
            Path dataFolder = Path.of("Data", fileName);
            if (Files.exists(dataFolder)) path = dataFolder;
        }
        if (!Files.exists(path)) {
            throw new FileNotFoundException("Dataset tidak ditemukan: " + fileName
                    + "\nLetakkan file CSV satu folder dengan VDRPMDPC.java atau dalam folder Data/.");
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() < POINTS + 2) {
            throw new IOException("Format CSV tidak sesuai (harus ada 2 baris header dan " + POINTS + " baris data): " + fileName);
        }
        double[][] matrix = new double[POINTS][POINTS];
        for (int i = 0; i < POINTS; i++) {
            String[] fields = lines.get(i + 2).split(";", -1);
            if (fields.length < POINTS + 2) {
                throw new IOException("Jumlah kolom tidak sesuai pada " + fileName + ", baris " + (i + 3));
            }
            for (int j = 0; j < POINTS; j++) {
                String value = fields[j + 2].trim().replace(",", ".");
                try {
                    matrix[i][j] = Double.parseDouble(value);
                } catch (NumberFormatException ex) {
                    throw new IOException("Nilai jarak bukan angka pada " + fileName
                            + ", baris " + (i + 3) + ", kolom " + (j + 3) + ": " + value, ex);
                }
            }
        }
        return matrix;
    }

    static void validateData() {
        if (DEMAND.length != POINTS || VAN.length != POINTS || DRONE.length != POINTS)
            throw new IllegalStateException("Jumlah baris/demand dataset tidak sesuai " + POINTS + " titik");
        int total=0;
        for (int i=0; i<POINTS; i++) {
            if (VAN[i].length != POINTS || DRONE[i].length != POINTS)
                throw new IllegalStateException("Matriks tidak " + POINTS + "x" + POINTS + ", baris " + i);
            if (DEMAND[i] < 0) throw new IllegalStateException("Demand negatif");
            total += DEMAND[i];
            for (int j=0; j<POINTS; j++)
                if (!Double.isFinite(VAN[i][j]) || VAN[i][j]<0 ||
                    !Double.isFinite(DRONE[i][j]) || DRONE[i][j]<0)
                    throw new IllegalStateException("Jarak tidak valid: " + i + "," + j);
        }
        if (total > VAN_CAPACITY)
            throw new IllegalStateException("Total muatan yang dibawa van dari depot > kapasitas van");
    }

    static class Sortie {
        final int launch, customer, recovery, launchIndex, recoveryIndex;
        final double duration;
        Sortie(int l, int c, int r, int li, int ri, double duration) {
            launch=l; customer=c; recovery=r; launchIndex=li; recoveryIndex=ri; this.duration=duration;
        }
        public String toString() { return launch+" -> "+customer+" -> "+recovery; }
    }
    static class Eval {
        final boolean feasible;
        final double objective;
        final List<Integer> vanRoute;
        final List<Sortie> sorties;
        Eval(boolean ok, double time, List<Integer> route, List<Sortie> missions) {
            feasible=ok; objective=time; vanRoute=route; sorties=missions;
        }
    }
    static class Candidate {
        final int[] order;            // permutasi semua pelanggan, 1..N
        final boolean[] drone;        // pilihan penugasan drone per pelanggan
        final byte[] anchor;          // variasi anchor 0,1,2
        Eval evaluation;
        Candidate(int[] o, boolean[] d, byte[] a) {order=o;drone=d;anchor=a;}
        Candidate copy() {
            Candidate c = new Candidate(order.clone(), drone.clone(), anchor.clone());
            c.evaluation=evaluation;
            return c;
        }
        double objective() {return evaluation.objective;}
    }
    static double missionDuration(int launch, int customer, int recovery) {
        return PREPARATION + LAUNCH + DRONE_SERVICE + LANDING
                + DRONE[launch][customer] * 3600.0/DRONE_KMH
                + DRONE[customer][recovery] * 3600.0/DRONE_KMH;
    }
    // Decode genotype menjadi satu rute van dan sortie drone yang tidak tumpang tindih.
    // Jika pilihan drone tidak valid, pelanggan itu tetap dilayani van.
    static Eval evaluate(Candidate candidate) {
        boolean[] chosen = candidate.drone.clone();
        for (int c=1;c<=N;c++) if (DEMAND[c] > DRONE_CAPACITY) chosen[c]=false;
        for (int repeat=0;repeat<=N;repeat++) {
            ArrayList<Integer> route = new ArrayList<>();
            route.add(0);
            for (int c : candidate.order) if (!chosen[c]) route.add(c);
            route.add(0);
            int[] vanIndex = new int[POINTS];
            Arrays.fill(vanIndex, -1);
            for (int i=1;i<route.size()-1;i++) vanIndex[route.get(i)]=i;
            ArrayList<Sortie> missions = new ArrayList<>();
            int previousRecoveryIndex = -1;
            int rejectCustomer=-1;
            for (int p=0;p<N;p++) {
                int c=candidate.order[p];
                if (!chosen[c]) continue;
                int left=-1, right=-1;
                for (int k=p-1;k>=0;k--) if (!chosen[candidate.order[k]]) {left=candidate.order[k];break;}
                for (int k=p+1;k<N;k++) if (!chosen[candidate.order[k]]) {right=candidate.order[k];break;}
                if (left<0 || right<0) {rejectCustomer=c;break;}
                int li=vanIndex[left], ri=vanIndex[right];
                int mode=Byte.toUnsignedInt(candidate.anchor[c])%3;
                if (mode==1 && li>1) li--;
                if (mode==2 && ri<route.size()-2) ri++;
                left=route.get(li);right=route.get(ri);
                double duration=missionDuration(left,c,right);
                if (li<1 || ri>=route.size()-1 || li>=ri ||
                    li<previousRecoveryIndex || duration>battery+EPS) {
                    rejectCustomer=c;break;
                }
                missions.add(new Sortie(left,c,right,li,ri,duration));
                previousRecoveryIndex=ri;
            }
            if (rejectCustomer>=0) {chosen[rejectCustomer]=false;continue;}
            // Semua pelanggan dilayani tepat sekali, dan muatan total <= kapasitas van.
            boolean[] served=new boolean[POINTS];
            int load=0;
            for (int i=1;i<route.size()-1;i++) {
                int c=route.get(i);if (served[c])return invalid();
                served[c]=true;
            }
            for (Sortie s:missions) {
                if (served[s.customer])return invalid();
                served[s.customer]=true;
            }
            for (int c=1;c<=N;c++) {if(!served[c])return invalid();load+=DEMAND[c];}
            if(load>VAN_CAPACITY)return invalid();
            // Simulasi jadwal van dan drone; satu drone boleh sortie berulang,
            // tetapi tidak boleh berada di dua penerbangan sekaligus.
            Sortie active=null;
            double droneReady=Double.NaN, departure=0.0;
            int previous=0;
            for (int idx=1;idx<route.size();idx++) {
                int node=route.get(idx);
                double arrival=departure+VAN[previous][node]*3600.0/VAN_KMH;
                double ready=arrival+(node==0?0.0:VAN_SERVICE);
                if (active!=null && active.recoveryIndex==idx) {
                    ready=Math.max(ready,droneReady);
                    active=null;
                }
                for (Sortie s:missions) if(s.launchIndex==idx) {
                    if(active!=null)return invalid();
                    // Preparation dimulai setelah van tiba dan drone sebelumnya kembali.
                    double droneDeparture=arrival+PREPARATION+LAUNCH;
                    ready=Math.max(ready,droneDeparture);
                    droneReady=droneDeparture+
                            DRONE[s.launch][s.customer]*3600.0/DRONE_KMH+
                            DRONE_SERVICE+
                            DRONE[s.customer][s.recovery]*3600.0/DRONE_KMH+LANDING;
                    active=s;
                }
                departure=ready;
                previous=node;
            }
            if (active!=null || !Double.isFinite(departure) || departure<=0)return invalid();
            return new Eval(true,departure,route,missions);
        }
        return invalid();
    }
    static Eval invalid() {return new Eval(false,Double.POSITIVE_INFINITY,List.of(),List.of());}
    static Candidate randomCandidate(Random rng) {
        int[] order=new int[N];for(int i=0;i<N;i++)order[i]=i+1;
        for(int i=N-1;i>0;i--)swap(order,i,rng.nextInt(i+1));
        boolean[] d=new boolean[POINTS];byte[] anchor=new byte[POINTS];
        for(int c=1;c<=N;c++) {
            d[c]=DEMAND[c]<=DRONE_CAPACITY && rng.nextDouble()<0.28;
            anchor[c]=(byte)rng.nextInt(3);
        }
        return new Candidate(order,d,anchor);
    }
    static void swap(int[] a,int i,int j){int t=a[i];a[i]=a[j];a[j]=t;}

    interface Search {
        Candidate optimize();
        long evaluations();
        double[] history();
    }
    static abstract class SearchBase implements Search {
        final int population, iterations;
        final Random rng;
        long count=0;
        final double[] trace;
        SearchBase(int p,int it,int seed) {population=p;iterations=it;rng=new Random(seed);trace=new double[it];}
        Candidate score(Candidate c) {c.evaluation=evaluate(c);count++;return c;}
        public long evaluations(){return count;}
        public double[] history(){return trace.clone();}
    }
    static class ACO extends SearchBase {
        static final double BETA=2.3, Q0=0.9, RHO=0.1;
        static final int CANDIDATE_LIST=5;
        final double[][] tau=new double[POINTS][POINTS];
        double tau0;
        ACO(int p,int it,int seed){super(p,it,seed);}
        // Tahap proses ACO (Ant Colony Optimization):
        // 1. Membuat solusi awal dan menginisialisasi nilai feromon.
        // 2. Semut membangun urutan kunjungan berdasarkan feromon dan jarak.
        // 3. Memperbaiki solusi dengan pencarian lokal 2-opt.
        // 4. Memperbarui feromon berdasarkan solusi semut dan solusi terbaik.
        // 5. Menyimpan objective terbaik pada setiap iterasi.
        public Candidate optimize(){
            Candidate best=score(randomCandidate(rng));
            tau0=1.0/best.objective();
            for(double[] row:tau)Arrays.fill(row,tau0);
            for(int iter=0;iter<iterations;iter++){
                for(int ant=0;ant<population;ant++){
                    Candidate c=score(construct());
                    // Local search 2-opt pada urutan kunjungan (maks 2 percobaan).
                    for(int attempt=0;attempt<2;attempt++){
                        Candidate neighbor=c.copy();
                        int i=rng.nextInt(N),j=rng.nextInt(N);
                        if(i>j){int t=i;i=j;j=t;}
                        while(i<j)swap(neighbor.order,i++,j--);
                        score(neighbor);
                        if(neighbor.objective()+EPS<c.objective())c=neighbor;
                    }
                    if(c.objective()+EPS<best.objective())best=c.copy();
                    localUpdate(c.evaluation.vanRoute);
                }
                // Pembaruan feromon global dan elitist best-so-far.
                for(int i=0;i<POINTS;i++)for(int j=0;j<POINTS;j++)tau[i][j]*=(1-RHO);
                List<Integer> route=best.evaluation.vanRoute;
                for(int i=1;i<route.size();i++)
                    tau[route.get(i-1)][route.get(i)]+=RHO/best.objective();
                trace[iter]=best.objective();
            }
            return best;
        }
        Candidate construct(){
            int[] order=new int[N];boolean[] used=new boolean[POINTS];
            int current=0;
            for(int k=0;k<N;k++){
                ArrayList<Integer> choices=new ArrayList<>();
                for(int c=1;c<=N;c++)if(!used[c])choices.add(c);
                final int from = current;
                choices.sort(Comparator.comparingDouble(c->VAN[from][c]));
                int take=Math.min(CANDIDATE_LIST,choices.size());
                int chosen;
                if(rng.nextDouble()<Q0){
                    double bestWeight=-1;chosen=choices.get(0);
                    for(int i=0;i<take;i++){
                        int c=choices.get(i);double w=weight(current,c);
                        if(w>bestWeight){bestWeight=w;chosen=c;}
                    }
                }else{
                    double total=0;for(int i=0;i<take;i++)total+=weight(current,choices.get(i));
                    double pick=rng.nextDouble()*total;chosen=choices.get(take-1);
                    for(int i=0;i<take;i++){
                        int c=choices.get(i);pick-=weight(current,c);
                        if(pick<=0){chosen=c;break;}
                    }
                }
                order[k]=chosen;used[chosen]=true;current=chosen;
            }
            boolean[] drone=new boolean[POINTS];byte[] anchor=new byte[POINTS];
            for(int c=1;c<=N;c++){
                drone[c]=DEMAND[c]<=DRONE_CAPACITY && rng.nextDouble()<0.28;
                anchor[c]=(byte)rng.nextInt(3);
            }
            return new Candidate(order,drone,anchor);
        }
        double weight(int from,int to){return Math.pow(Math.max(tau[from][to],1e-14),1.0)
                *Math.pow(1.0/Math.max(VAN[from][to],1e-6),BETA);}
        void localUpdate(List<Integer> route){
            for(int i=1;i<route.size();i++){
                int a=route.get(i-1),b=route.get(i);
                tau[a][b]=(1-RHO)*tau[a][b]+RHO*tau0;
            }
        }
    }
    static class ABC extends SearchBase {
        static final int LIMIT=100;
        ABC(int p,int it,int seed){super(p,it,seed);}
        // Tahap proses ABC (Artificial Bee Colony):
        // 1. Membuat populasi awal; setiap sumber makanan mewakili satu solusi.
        // 2. Employed bees mencoba memperbaiki solusi pada sumber masing-masing.
        // 3. Onlooker bees memilih sumber berdasarkan kualitas, lalu mencoba
        //    memperbaiki solusi yang dipilih.
        // 4. Scout bees mengganti sumber makanan yang terlalu lama stagnan.
        // 5. Menyimpan solusi terbaik pada setiap iterasi.
        public Candidate optimize(){
            Candidate[] sources=new Candidate[population];int[] failures=new int[population];
            Candidate best=null;
            for(int i=0;i<population;i++){
                sources[i]=score(randomCandidate(rng));
                if(best==null || sources[i].objective()<best.objective())best=sources[i].copy();
            }
            for(int iter=0;iter<iterations;iter++){
                // Employed bees: satu lebah per food source.
                for(int i=0;i<population;i++){
                    Candidate neighbor=score(mutate(sources[i]));
                    if(neighbor.objective()+EPS<sources[i].objective()){
                        sources[i]=neighbor;failures[i]=0;
                    }else failures[i]++;
                    if(sources[i].objective()+EPS<best.objective())best=sources[i].copy();
                }
                // Onlooker bees: N onlooker untuk N food source.
                double[] probabilities=new double[population];double sum=0;
                for(int i=0;i<population;i++){
                    probabilities[i]=1.0/Math.max(sources[i].objective(),EPS);
                    sum+=probabilities[i];
                }
                for(int bee=0;bee<population;bee++){
                    double r=rng.nextDouble()*sum;int index=population-1;
                    for(int i=0;i<population;i++)if((r-=probabilities[i])<=0){index=i;break;}
                    Candidate neighbor=score(mutate(sources[index]));
                    if(neighbor.objective()+EPS<sources[index].objective()){
                        sources[index]=neighbor;failures[index]=0;
                    }else failures[index]++;
                    if(sources[index].objective()+EPS<best.objective())best=sources[index].copy();
                }
                // Scout bee: sumber yang stagnan selama >=100 kegagalan.
                for(int i=0;i<population;i++)if(failures[i]>=LIMIT){
                    sources[i]=score(randomCandidate(rng));failures[i]=0;
                    if(sources[i].objective()+EPS<best.objective())best=sources[i].copy();
                }
                trace[iter]=best.objective();
            }
            return best;
        }
        Candidate mutate(Candidate parent){
            Candidate c=parent.copy();
            int i=rng.nextInt(N),j=rng.nextInt(N);
            while(j==i)j=rng.nextInt(N);
            switch(rng.nextInt(5)){
                case 0 -> swap(c.order,i,j); // swap
                case 1 -> { // insertion
                    int v=c.order[i];
                    if(i<j)System.arraycopy(c.order,i+1,c.order,i,j-i);
                    else System.arraycopy(c.order,j,c.order,j+1,i-j);
                    c.order[j]=v;
                }
                case 2 -> { // reverse
                    if(i>j){int t=i;i=j;j=t;}
                    while(i<j)swap(c.order,i++,j--);
                }
                case 3 -> { // drone assignment
                    int customer=c.order[i];
                    if(DEMAND[customer]<=DRONE_CAPACITY)c.drone[customer]=!c.drone[customer];
                    else swap(c.order,i,j);
                }
                case 4 -> { // drone anchor
                    int customer=c.order[i];
                    c.anchor[customer]=(byte)((Byte.toUnsignedInt(c.anchor[customer])+1+rng.nextInt(2))%3);
                    if(!c.drone[customer] && DEMAND[customer]<=DRONE_CAPACITY)
                        c.drone[customer]=true;
                }
            }
            return c;
        }
    }

    record RunRecord(String algorithm,int population,int seed,double objective,
                     double fitness,double seconds,long evaluations,Eval best) {}
    record Stats(String algorithm,int population,double meanObjective,double meanFitness,
                 double meanSeconds,double stdObjective,double meanEvaluations) {}
    static List<Stats> aggregate(List<RunRecord> records){
        ArrayList<Stats> result=new ArrayList<>();
        for(int pop:POPULATIONS)for(String alg:new String[]{"ACO","ABC"}){
            List<RunRecord> rs=new ArrayList<>();
            for(RunRecord r:records)if(r.population()==pop && r.algorithm().equals(alg))rs.add(r);
            double so=0,sf=0,st=0,se=0;
            for(RunRecord r:rs){so+=r.objective();sf+=r.fitness();st+=r.seconds();se+=r.evaluations();}
            double mo=so/rs.size(),var=0;
            for(RunRecord r:rs)var+=Math.pow(r.objective()-mo,2);
            result.add(new Stats(alg,pop,mo,sf/rs.size(),st/rs.size(),
                    rs.size()>1?Math.sqrt(var/(rs.size()-1)):0,se/rs.size()));
        }
        return result;
    }
    static Path outputPath(String fileName) throws IOException {
        // CSV dan laporan TXT berada di folder proyek, bersama file Java dan dataset.
        return Path.of(fileName);
    }

    static void writeRaw(List<RunRecord> records) throws IOException {
        ArrayList<String> lines=new ArrayList<>();
        lines.add("instans,algoritma,konfigurasi,populasi,run,status,objective_detik,fitness,waktu_komputasi_detik");
        for(RunRecord r:records){
            String config=r.population()==20?"A":r.population()==50?"B":"C";
            lines.add(String.format(LOCALE,"%s,%s,%s,%d,%d,FEASIBLE,%.8f,%.12f,%.8f",
                    DATASET,r.algorithm(),config,r.population(),r.seed(),r.objective(),r.fitness(),r.seconds()));
        }
        Files.write(outputPath("hasil_run"+outputSuffix+".csv"),lines,StandardCharsets.UTF_8);
    }
    static void writeSummary(List<RunRecord> records) throws IOException {
        ArrayList<String> lines=new ArrayList<>();
        lines.add("instans,populasi,mean_objective_aco_detik,mean_objective_abc_detik,mean_fitness_aco,mean_fitness_abc,mean_komputasi_aco_detik,mean_komputasi_abc_detik");
        List<Stats> stats=aggregate(records);
        for(int pop:POPULATIONS){
            Stats a=null,b=null;
            for(Stats s:stats)if(s.population()==pop){if(s.algorithm().equals("ACO"))a=s;else b=s;}
            lines.add(String.format(LOCALE,"%s,%d,%.8f,%.8f,%.12f,%.12f,%.8f,%.8f",
                    DATASET,pop,a.meanObjective(),b.meanObjective(),a.meanFitness(),b.meanFitness(),
                    a.meanSeconds(),b.meanSeconds()));
        }
        Files.write(outputPath("rekap"+outputSuffix+".csv"),lines,StandardCharsets.UTF_8);
    }
    static void writeBestConfiguration(List<RunRecord> records) throws IOException {
        Stats bestA=null, bestB=null;
        for(Stats s:aggregate(records)) {
            if(s.algorithm().equals("ACO")) {
                if(bestA==null || s.meanObjective()<bestA.meanObjective())bestA=s;
            } else if(bestB==null || s.meanObjective()<bestB.meanObjective())bestB=s;
        }
        String winner=Math.abs(bestA.meanObjective()-bestB.meanObjective())<1e-8?"SETARA":
                bestA.meanObjective()<bestB.meanObjective()?"ACO":"ABC";
        List<String> lines=List.of(
                "instans,populasi_terbaik_aco,mean_objective_aco_detik,populasi_terbaik_abc,mean_objective_abc_detik,algoritma_unggul",
                String.format(LOCALE,"%s,%d,%.8f,%d,%.8f,%s",
                        DATASET,bestA.population(),bestA.meanObjective(),bestB.population(),bestB.meanObjective(),winner));
        Files.write(outputPath("tabel2_terbaik"+outputSuffix+".csv"),lines,StandardCharsets.UTF_8);
    }
    static void printSummary(List<RunRecord> records, PrintStream out){
        out.println("\n================ TABEL 1: RATA-RATA " + runs + " RUN ================");
        out.println("Pop | Obj ACO(s) | Obj ABC(s) | Fitness ACO | Fitness ABC | Komputasi ACO(s) | Komputasi ABC(s)");
        List<Stats> stats=aggregate(records);
        Stats bestA=null,bestB=null;
        for(int pop:POPULATIONS){
            Stats a=null,b=null;
            for(Stats s:stats)if(s.population()==pop){if(s.algorithm().equals("ACO"))a=s;else b=s;}
            out.printf(LOCALE,"%3d | %10.2f | %10.2f | %.10f | %.10f | %10.4f | %10.4f%n",
                    pop,a.meanObjective(),b.meanObjective(),a.meanFitness(),b.meanFitness(),
                    a.meanSeconds(),b.meanSeconds());
            if(bestA==null||a.meanObjective()<bestA.meanObjective())bestA=a;
            if(bestB==null||b.meanObjective()<bestB.meanObjective())bestB=b;
        }
        out.println("\n================ TABEL 2: KONFIGURASI TERBAIK ================");
        out.printf(LOCALE,"%s | ACO: populasi %d, mean %.2f s | ABC: populasi %d, mean %.2f s%n",
                DATASET,bestA.population(),bestA.meanObjective(),bestB.population(),bestB.meanObjective());
        String winner=Math.abs(bestA.meanObjective()-bestB.meanObjective())<1e-8?"SETARA":
                bestA.meanObjective()<bestB.meanObjective()?"ACO":"ABC";
        out.println("Algoritma unggul (mean objective terkecil): "+winner);
        out.println("Mean fitness adalah rata-rata 1/objective setiap run, BUKAN 1/mean objective.");
    }
    static void printBestRoutes(List<RunRecord> records, PrintStream out){
        for(String alg:new String[]{"ACO","ABC"}){
            RunRecord best=null;
            for(RunRecord r:records)if(r.algorithm().equals(alg) && (best==null||r.objective()<best.objective()))best=r;
            out.printf(LOCALE,"\nRUTE TERBAIK %s (seluruh konfigurasi): pop=%d obj=%.2f detik%n",
                    alg,best.population(),best.objective());
            out.println("Van: "+best.best().vanRoute);
            if(best.best().sorties.isEmpty())out.println("Drone: tidak digunakan pada solusi terbaik ini");
            else for(Sortie s:best.best().sorties)
                out.printf(LOCALE,"Drone: %s | durasi operasi %.2f s%n",s,s.duration);
        }
    }
    static void printWinnerSummary(List<RunRecord> records, PrintStream out){
        Stats bestA=null,bestB=null;
        for(Stats s:aggregate(records)){
            if(s.algorithm().equals("ACO")){
                if(bestA==null||s.meanObjective()<bestA.meanObjective())bestA=s;
            }else if(bestB==null||s.meanObjective()<bestB.meanObjective())bestB=s;
        }
        String winner=Math.abs(bestA.meanObjective()-bestB.meanObjective())<1e-8?"SETARA":
                bestA.meanObjective()<bestB.meanObjective()?"ACO":"ABC";
        out.println("\n================ KESIMPULAN SINGKAT ================");
        out.println("Algoritma | Mean Objective terbaik (detik) | Hasil");
        out.printf(LOCALE,"ACO       | %29.2f | %s%n",bestA.meanObjective(),
                winner.equals("ACO")?"TERBAIK":"");
        out.printf(LOCALE,"ABC       | %29.2f | %s%n",bestB.meanObjective(),
                winner.equals("ABC")?"TERBAIK":"");
        out.println("Kesimpulan: "+winner);
    }
}
