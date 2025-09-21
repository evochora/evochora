package org.evochora.datapipeline.storage.impl.h2;

import com.typesafe.config.Config;
import org.evochora.datapipeline.storage.api.indexer.IEnvironmentStateWriter;
import org.evochora.datapipeline.storage.api.indexer.model.EnvironmentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.h2.tools.Server;

import java.sql.*;
import java.util.List;

/**
 * H2 database implementation of the simulation repository.
 * 
 * <p>This repository handles persistent storage of simulation data using H2 database.
 * It implements the IEnvironmentStateWriter interface for storing environment state
 * information and can be extended to implement additional storage interfaces.</p>
 * 
 * <p>The repository automatically creates tables based on the configuration and
 * handles n-dimensional position data by creating separate columns for each dimension.</p>
 * 
 * @author evochora
 * @since 1.0
 */
public class H2SimulationRepository implements IEnvironmentStateWriter {
    
    private static final Logger logger = LoggerFactory.getLogger(H2SimulationRepository.class);
    
    private final String jdbcUrlTemplate;
    private final String user;
    private final String password;
    private final boolean initializeSchema;
    private String actualJdbcUrl;
    
    // Web Console configuration
    private final boolean webConsoleEnabled;
    private final int webConsolePort;
    private final boolean webConsoleAllowOthers;
    private final boolean webConsoleSSL;
    private final String webConsolePath;
    private final String webConsoleAdminPassword;
    private final boolean webConsoleBrowser;
    
    private Connection connection;
    private PreparedStatement insertStatement;
    private int dimensions = 0; // Environment dimensions set during initialization
    private Server webConsoleServer;
    
    /**
     * Creates a new H2SimulationRepository with the specified configuration.
     * 
     * @param config the configuration object containing H2 settings
     */
    public H2SimulationRepository(Config config) {
        if (!config.hasPath("jdbcUrl")) {
            logger.error("Missing required configuration: jdbcUrl. Cannot initialize H2SimulationRepository without database URL.");
            throw new IllegalArgumentException("jdbcUrl configuration is required");
        }
        this.jdbcUrlTemplate = config.getString("jdbcUrl");
        
        if (!config.hasPath("user")) {
            logger.warn("Missing configuration: user. H2 will use default user 'sa' which may not be suitable for production.");
        }
        this.user = config.hasPath("user") ? config.getString("user") : "sa";
        
        if (!config.hasPath("password")) {
            logger.warn("Missing configuration: password. H2 will use empty password which may not be suitable for production.");
        }
        this.password = config.hasPath("password") ? config.getString("password") : "";
        
        if (!config.hasPath("initializeSchema")) {
            logger.warn("Missing configuration: initializeSchema. Defaulting to true - schema will be auto-created.");
        }
        this.initializeSchema = config.hasPath("initializeSchema") ? config.getBoolean("initializeSchema") : true;
        
        // Parse Web Console configuration
        Config webConsoleConfig = config.hasPath("webConsole") ? config.getConfig("webConsole") : 
            com.typesafe.config.ConfigFactory.empty();
        
        if (!webConsoleConfig.hasPath("enabled")) {
            logger.debug("Web Console configuration missing: enabled. Defaulting to disabled.");
        }
        this.webConsoleEnabled = webConsoleConfig.hasPath("enabled") ? webConsoleConfig.getBoolean("enabled") : false;
        
        if (webConsoleEnabled && !webConsoleConfig.hasPath("port")) {
            logger.warn("Web Console enabled but missing configuration: port. Defaulting to 8082.");
        }
        this.webConsolePort = webConsoleConfig.hasPath("port") ? webConsoleConfig.getInt("port") : 8082;
        
        if (webConsoleEnabled && !webConsoleConfig.hasPath("allowOthers")) {
            logger.debug("Web Console configuration missing: allowOthers. Defaulting to false (localhost only).");
        }
        this.webConsoleAllowOthers = webConsoleConfig.hasPath("allowOthers") ? webConsoleConfig.getBoolean("allowOthers") : false;
        
        if (webConsoleEnabled && !webConsoleConfig.hasPath("webSSL")) {
            logger.debug("Web Console configuration missing: webSSL. Defaulting to false (HTTP).");
        }
        this.webConsoleSSL = webConsoleConfig.hasPath("webSSL") ? webConsoleConfig.getBoolean("webSSL") : false;
        
        if (webConsoleEnabled && !webConsoleConfig.hasPath("webPath")) {
            logger.debug("Web Console configuration missing: webPath. Defaulting to '/console'.");
        }
        this.webConsolePath = webConsoleConfig.hasPath("webPath") ? webConsoleConfig.getString("webPath") : "/console";
        
        if (webConsoleEnabled && !webConsoleConfig.hasPath("webAdminPassword")) {
            logger.warn("Web Console enabled but missing configuration: webAdminPassword. Console will be accessible without authentication.");
        }
        this.webConsoleAdminPassword = webConsoleConfig.hasPath("webAdminPassword") ? webConsoleConfig.getString("webAdminPassword") : null;
        
        if (webConsoleEnabled && !webConsoleConfig.hasPath("browser")) {
            logger.debug("Web Console configuration missing: browser. Defaulting to false (no auto-open).");
        }
        this.webConsoleBrowser = webConsoleConfig.hasPath("browser") ? webConsoleConfig.getBoolean("browser") : false;
    }
    
    @Override
    public void initialize(int dimensions, String simulationRunId) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Dimensions must be greater than 0, got: " + dimensions);
        }
        if (simulationRunId == null || simulationRunId.trim().isEmpty()) {
            throw new IllegalArgumentException("SimulationRunId cannot be null or empty");
        }
        
        this.dimensions = dimensions;
        
        // Replace placeholder in JDBC URL template with actual simulation run ID
        this.actualJdbcUrl = jdbcUrlTemplate.replace("{simulationRunId}", simulationRunId);
        
        logger.info("Initializing H2SimulationRepository with URL: {} and {} dimensions for simulation run: {}", 
            actualJdbcUrl, dimensions, simulationRunId);
        
        try {
            // Explicitly load H2 driver
            Class.forName("org.h2.Driver");
            connection = DriverManager.getConnection(actualJdbcUrl, user, password);
            
            if (initializeSchema) {
                createEnvironmentStateTable();
            }
            
            // Start Web Console if enabled
            if (webConsoleEnabled) {
                startWebConsole();
            }
            
            logger.info("H2SimulationRepository initialized successfully");
        } catch (ClassNotFoundException e) {
            logger.error("H2 driver not found in classpath", e);
            throw new RuntimeException("H2 driver not available", e);
        } catch (SQLException e) {
            logger.error("Failed to initialize H2SimulationRepository", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }
    
    /**
     * Closes the database connection and releases resources.
     * Implements IEnvironmentStateWriter.close() interface method.
     * 
     * @throws RuntimeException if closing fails
     */
    @Override
    public void close() {
        try {
            // Stop Web Console if running
            if (webConsoleServer != null && webConsoleServer.isRunning(false)) {
                try {
                    webConsoleServer.stop();
                    logger.info("H2 Web Console stopped");
                } catch (Exception e) {
                    logger.warn("Failed to stop H2 Web Console", e);
                }
            }
            
            if (insertStatement != null) {
                insertStatement.close();
            }
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
            logger.info("H2SimulationRepository closed");
        } catch (SQLException e) {
            logger.error("Failed to close H2SimulationRepository", e);
            throw new RuntimeException("Failed to close H2SimulationRepository", e);
        }
    }
    
    @Override
    public void writeEnvironmentStates(List<EnvironmentState> environmentStates) {
        if (environmentStates == null) {
            throw new IllegalArgumentException("Environment states list cannot be null");
        }
        
        if (environmentStates.isEmpty()) {
            logger.debug("No environment states to write");
            return;
        }
        
        if (dimensions == 0) {
            throw new IllegalStateException("Repository not initialized. Call initialize() first.");
        }
        
        try {
            // Validate dimensions match
            for (EnvironmentState state : environmentStates) {
                if (state.position().getDimensions() != dimensions) {
                    throw new IllegalArgumentException(
                        String.format("Position dimensions mismatch: expected %d, got %d", 
                            dimensions, state.position().getDimensions()));
                }
            }
            
            // Prepare batch insert
            if (insertStatement == null) {
                prepareInsertStatement();
            }
            
            // Disable auto-commit for atomic batch operation
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            
            try {
                // Execute batch insert
                for (EnvironmentState state : environmentStates) {
                    setInsertParameters(insertStatement, state);
                    insertStatement.addBatch();
                }
                
                int[] results = insertStatement.executeBatch();
                
                // Commit the entire batch
                connection.commit();
                
                logger.debug("Successfully inserted {} environment state records atomically", results.length);
                
            } catch (SQLException e) {
                // Rollback the entire batch on any error
                try {
                    connection.rollback();
                    logger.warn("Rolled back batch insert due to error: {}", e.getMessage());
                } catch (SQLException rollbackException) {
                    logger.error("Failed to rollback batch insert", rollbackException);
                }
                throw e;
            } finally {
                // Restore original auto-commit setting
                connection.setAutoCommit(originalAutoCommit);
            }
            
        } catch (SQLException e) {
            logger.error("Failed to write environment states to database", e);
            throw new RuntimeException("Database write operation failed", e);
        }
    }
    
    /**
     * Creates the environment_state table with dynamic columns for position dimensions.
     */
    private void createEnvironmentStateTable() throws SQLException {
        String sql = buildCreateTableSQL();
        logger.info("Creating environment_state table with SQL: {}", sql);
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
        
        // Create indexes for efficient querying
        createIndexes();
        
        logger.info("Environment state table created successfully");
    }
    
    
    /**
     * Builds the CREATE TABLE SQL statement with dynamic position columns.
     */
    private String buildCreateTableSQL() {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE IF NOT EXISTS environment_state (");
        sql.append("tick BIGINT NOT NULL,");
        sql.append("molecule_type VARCHAR(255) NOT NULL,");
        sql.append("molecule_value INT NOT NULL,");
        sql.append("owner BIGINT NOT NULL");
        
        // Add position columns for each dimension
        for (int i = 0; i < dimensions; i++) {
            sql.append(",pos_").append(i).append(" INT NOT NULL");
        }
        
        sql.append(")");
        
        return sql.toString();
    }
    
    /**
     * Creates indexes for efficient querying.
     */
    private void createIndexes() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Primary index on tick and position
            StringBuilder indexSql = new StringBuilder();
            indexSql.append("CREATE INDEX IF NOT EXISTS idx_env_state_tick_pos ON environment_state (tick");
            for (int i = 0; i < dimensions; i++) {
                indexSql.append(",pos_").append(i);
            }
            indexSql.append(")");
            
            stmt.execute(indexSql.toString());
            logger.debug("Created index: {}", indexSql.toString());
        }
    }
    
    /**
     * Prepares the insert statement based on current dimensions.
     */
    private void prepareInsertStatement() throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO environment_state (tick, molecule_type, molecule_value, owner");
        
        for (int i = 0; i < dimensions; i++) {
            sql.append(",pos_").append(i);
        }
        
        sql.append(") VALUES (?, ?, ?, ?");
        for (int i = 0; i < dimensions; i++) {
            sql.append(",?");
        }
        sql.append(")");
        
        insertStatement = connection.prepareStatement(sql.toString());
        logger.debug("Prepared insert statement: {}", sql.toString());
    }
    
    /**
     * Sets the parameters for the insert statement.
     */
    private void setInsertParameters(PreparedStatement stmt, EnvironmentState state) throws SQLException {
        stmt.setLong(1, state.tick());
        stmt.setString(2, state.moleculeType());
        stmt.setInt(3, state.moleculeValue());
        stmt.setLong(4, state.owner());
        
        // Set position coordinates
        int[] coordinates = state.position().coordinates();
        for (int i = 0; i < dimensions; i++) {
            stmt.setInt(5 + i, coordinates[i]);
        }
    }
    
    /**
     * Starts the H2 Web Console server with the configured options.
     */
    private void startWebConsole() {
        try {
            // H2 Web Console with minimal supported options only
            String[] args = {
                "-web",
                "-webPort", String.valueOf(webConsolePort)
            };
            
            // Only add -webAllowOthers if it's true (no value needed)
            if (webConsoleAllowOthers) {
                String[] argsWithAllowOthers = new String[args.length + 1];
                System.arraycopy(args, 0, argsWithAllowOthers, 0, args.length);
                argsWithAllowOthers[args.length] = "-webAllowOthers";
                args = argsWithAllowOthers;
            }
            
            webConsoleServer = Server.createWebServer(args);
            webConsoleServer.start();
            
            logger.info("H2 Web Console started on port {} at http://localhost:{}", 
                webConsolePort, webConsolePort);
            logger.info("===============================================");
            logger.info("H2 WEB CONSOLE CONNECTION INFO:");
            logger.info("===============================================");
            logger.info("🌐 Open: http://localhost:{}", webConsolePort);
            logger.info("📋 JDBC URL: {}", actualJdbcUrl);
            logger.info("👤 User: {}", user);
            logger.info("🔑 Password: {}", password.isEmpty() ? "(empty)" : "***");
            logger.info("===============================================");
            logger.info("💡 Copy these values into the H2 Web Console login form");
            
        } catch (Exception e) {
            logger.error("Failed to start H2 Web Console", e);
            // Don't throw exception - Web Console is optional
        }
    }
    
}
