package fr.ses10doigts.tradeIO5.service.doctor;

/** Constantes du Docteur (journal des jobs, rétention) et noms de jobs partagés entre jobs et contrôles. */
public final class DoctorDefaults {

    private DoctorDefaults() {
    }

    /** Rétention du journal des jobs et des alarmes résolues. */
    public static final int JOB_RUN_RETENTION_DAYS = 90;

    // --- Passes du Docteur
    public static final String INTERVAL_PROPERTY = "tradeio.doctor.interval-ms";
    /** Une passe toutes les 5 minutes. */
    public static final String INTERVAL_MS = "${" + INTERVAL_PROPERTY + ":300000}";
    public static final String INITIAL_DELAY_MS = "${tradeio.doctor.initial-delay-ms:60000}";

    /** Jobs dont le Docteur exige un cron actif et une exécution à l'heure (liste séparée par des virgules, surchargeable par profil). */
    public static final String EXPECTED_JOBS_PROPERTY = "tradeio.doctor.expected-jobs";
    public static final String EXPECTED_JOBS_DEFAULT = DoctorDefaults.JOB_RAINBOW_PASS + "," + DoctorDefaults.JOB_EXECUTION_PLAN + ","
            + DoctorDefaults.JOB_EXECUTION;

    // --- Seuils (globaux)
    /** Tolérance entre l'échéance du cron et le démarrage tracé du job. */
    public static final int JOB_START_SLACK_SECONDS = 60;
    /** Délai de grâce après une échéance avant de déclarer le job en retard. */
    public static final int JOB_GRACE_MINUTES = 10;
    /** Plans examinés : ceux des N derniers jours. */
    public static final int PLAN_WINDOW_DAYS = 3;
    /** Plan resté {@code EXECUTING} au-delà de ce délai = bloqué. */
    public static final int PLAN_STUCK_MINUTES = 30;
    /** Stock intermédiaire en transit au-delà de ce délai = anormal. */
    public static final int TRANSIT_STOCK_MAX_HOURS = 12;
    /** Kill switch laissé levé au-delà de ce délai = probable oubli. */
    public static final int KILL_SWITCH_LIFTED_WARN_HOURS = 6;
    public static final long DB_SLOW_MS = 1000;
    /** Espace disque libre (pourcentage) : en dessous, WARN puis CRIT. */
    public static final int DISK_WARN_FREE_PCT = 15;
    public static final int DISK_CRIT_FREE_PCT = 5;
    /** Fenêtre de comptage des logs et seuil de WARN avant alarme (les ERROR alarment dès le premier). */
    public static final int LOG_WINDOW_HOURS = 24;
    public static final int LOG_WARN_THRESHOLD = 50;
    public static final int LOG_BUFFER_MAX = 2000;
    public static final int LOG_MESSAGE_MAX = 300;
    public static final int SNOOZE_MAX_HOURS = 720;

    public static final String PURGE_CRON_PROPERTY = "tradeio.doctor.purge-cron";
    /** 03:30 heure JVM, tous les jours (actif par défaut). */
    public static final String PURGE_CRON = "${" + PURGE_CRON_PROPERTY + ":0 30 3 * * *}";

    // Noms de jobs tels qu'écrits dans job_run (DecisionOrchestratorJob volontairement absent : hors périmètre du Docteur).
    public static final String JOB_RAINBOW_PASS = "RainbowLivePass2355Job";
    public static final String JOB_EXECUTION_PLAN = "ExecutionPlanJob";
    public static final String JOB_EXECUTION = "ExecutionJob";
    public static final String JOB_HISTORICAL_BACKUP = "HistoricalDataBackupJob";
    public static final String JOB_MEDIA_INGESTION = "MediaWatchIngestionJob";
    public static final String JOB_MEDIA_EXTRACTION = "MediaWatchExtractionJob";
    public static final String JOB_ETF_FLOW = "EtfFlowHistorizationJob";
    public static final String JOB_SNAPSHOT = "DecisionScenarioSnapshotJob";
    public static final String JOB_ARCHIVAL = "UserArchivalJob";
}
