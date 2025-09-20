package org.evochora.datapipeline.storage.impl.h2;

import com.typesafe.config.Config;
import org.evochora.datapipeline.storage.api.indexer.IEnvironmentStateWriter;
import org.evochora.datapipeline.storage.api.indexer.model.EnvironmentState;
import org.evochora.datapipeline.storage.api.indexer.model.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    
    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final boolean initializeSchema;
    
    private Connection connection;
    private PreparedStatement insertStatement;
    private int dimensions = 0; // Environment dimensions set during initialization
    
    /**
     * Creates a new H2SimulationRepository with the specified configuration.
     * 
     * @param config the configuration object containing H2 settings
     */
    public H2SimulationRepository(Config config) {
        this.jdbcUrl = config.hasPath("jdbcUrl") ? config.getString("jdbcUrl") : 
            "jdbc:h2:file:./evochora_data/simulation_db;MV_STORE=FALSE;TRACE_LEVEL_FILE=0";
        this.user = config.hasPath("user") ? config.getString("user") : "sa";
        this.password = config.hasPath("password") ? config.getString("password") : "";
        this.initializeSchema = config.hasPath("initializeSchema") ? config.getBoolean("initializeSchema") : true;
    }
    
    @Override
    public void initialize(int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Dimensions must be greater than 0, got: " + dimensions);
        }
        
        this.dimensions = dimensions;
        logger.info("Initializing H2SimulationRepository with URL: {} and {} dimensions", jdbcUrl, dimensions);
        
        try {
            connection = DriverManager.getConnection(jdbcUrl, user, password);
            
            if (initializeSchema) {
                createEnvironmentStateTable();
            }
            
            logger.info("H2SimulationRepository initialized successfully");
        } catch (SQLException e) {
            logger.error("Failed to initialize H2SimulationRepository", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }
    
    /**
     * Closes the database connection and releases resources.
     * 
     * @throws SQLException if closing fails
     */
    public void close() throws SQLException {
        if (insertStatement != null) {
            insertStatement.close();
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        logger.info("H2SimulationRepository closed");
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
        sql.append("CREATE TABLE environment_state (");
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
            indexSql.append("CREATE INDEX idx_env_state_tick_pos ON environment_state (tick");
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
    
}
