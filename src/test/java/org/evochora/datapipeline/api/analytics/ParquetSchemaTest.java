package org.evochora.datapipeline.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ParquetSchema}: what a column says about the way it reaches a coarser
 * level of detail, and that a table and its columns say what they hold.
 */
@Tag("unit")
class ParquetSchemaTest {

    @Test
    void aColumnIsSampledUnlessItSaysOtherwise() {
        ParquetSchema schema = ParquetSchema.builder("A table.")
            .column("tick", ColumnType.BIGINT, "A value.")
            .column("avg_energy", ColumnType.DOUBLE, "A value.")
            .build();

        assertThat(schema.getColumns())
            .extracting(ParquetSchema.Column::aggregation)
            .containsOnly(Aggregation.SAMPLE);
        assertThat(schema.summedColumnIndexes()).isEmpty();
    }

    @Test
    void aColumnCarriesTheAggregationItWasDeclaredWith() {
        ParquetSchema schema = ParquetSchema.builder("A table.")
            .column("tick", ColumnType.BIGINT, "A value.")
            .column("births", ColumnType.INTEGER, Aggregation.SUM, "A value.")
            .column("alive", ColumnType.INTEGER, "A value.")
            .column("deaths", ColumnType.BIGINT, Aggregation.SUM, "A value.")
            .build();

        assertThat(schema.getColumns())
            .extracting(ParquetSchema.Column::name, ParquetSchema.Column::aggregation)
            .containsExactly(
                tuple("tick", Aggregation.SAMPLE),
                tuple("births", Aggregation.SUM),
                tuple("alive", Aggregation.SAMPLE),
                tuple("deaths", Aggregation.SUM));
    }

    @Test
    void theSummedColumnsAreFoundByTheirPositionInTheRow() {
        ParquetSchema schema = ParquetSchema.builder("A table.")
            .column("tick", ColumnType.BIGINT, "A value.")
            .column("births", ColumnType.INTEGER, Aggregation.SUM, "A value.")
            .column("alive", ColumnType.INTEGER, "A value.")
            .column("deaths", ColumnType.BIGINT, Aggregation.SUM, "A value.")
            .build();

        assertThat(schema.summedColumnIndexes()).containsExactly(1, 3);
    }

    @Test
    void aColumnThatCannotBeAddedCannotBeSummed() {
        // Adding the averages of a window produces a number nothing measures, and adding text or
        // a flag produces nothing at all
        assertThatThrownBy(() -> ParquetSchema.builder("A table.")
                .column("avg_energy", ColumnType.DOUBLE, Aggregation.SUM, "A value."))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("avg_energy")
            .hasMessageContaining("INTEGER or BIGINT");

        assertThatThrownBy(() -> ParquetSchema.builder("A table.")
                .column("kind", ColumnType.VARCHAR, Aggregation.SUM, "A value."))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("kind");

        assertThatThrownBy(() -> ParquetSchema.builder("A table.")
                .column("is_dead", ColumnType.BOOLEAN, Aggregation.SUM, "A value."))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("is_dead");
    }

    @Test
    void bothWholeNumberTypesCanBeSummed() {
        ParquetSchema schema = ParquetSchema.builder("A table.")
            .column("births", ColumnType.INTEGER, Aggregation.SUM, "A value.")
            .column("energy_spent", ColumnType.BIGINT, Aggregation.SUM, "A value.")
            .build();

        assertThat(schema.summedColumnIndexes()).containsExactly(0, 1);
    }

    @Test
    void aColumnWithoutAnAggregationIsNoColumn() {
        assertThatThrownBy(() -> ParquetSchema.builder("A table.").column("births", ColumnType.INTEGER, null, "A value."))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("aggregation");
    }

    @Test
    void theTableTheSchemaCreatesCarriesTypesRegardlessOfAggregation() {
        ParquetSchema schema = ParquetSchema.builder("A table.")
            .column("tick", ColumnType.BIGINT, "A value.")
            .column("births", ColumnType.INTEGER, Aggregation.SUM, "A value.")
            .build();

        assertThat(schema.toCreateTableSql("metric_lod0"))
            .isEqualTo("CREATE TABLE metric_lod0 (tick BIGINT, births INTEGER)");
        assertThat(schema.toInsertSql("metric_lod0"))
            .isEqualTo("INSERT INTO metric_lod0 VALUES (?, ?)");
    }

    @Test
    void theTableAndItsColumnsCarryTheirDescriptions() {
        ParquetSchema schema = ParquetSchema.builder("One row per recording.")
            .column("tick", ColumnType.BIGINT, "Tick of the recording.")
            .column("births", ColumnType.INTEGER, Aggregation.SUM, "Organisms born since the previous row.")
            .build();

        assertThat(schema.getDescription()).isEqualTo("One row per recording.");
        assertThat(schema.getColumns())
            .extracting(ParquetSchema.Column::name, ParquetSchema.Column::description)
            .containsExactly(
                tuple("tick", "Tick of the recording."),
                tuple("births", "Organisms born since the previous row."));
    }

    @Test
    void aTableWithoutADescriptionIsNoTable() {
        assertThatThrownBy(() -> ParquetSchema.builder(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("description");
        assertThatThrownBy(() -> ParquetSchema.builder("  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("description");
    }

    @Test
    void aColumnWithoutADescriptionIsNoColumn() {
        assertThatThrownBy(() -> ParquetSchema.builder("A table.").column("births", ColumnType.INTEGER, (String) null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("births")
            .hasMessageContaining("description");
        assertThatThrownBy(() -> ParquetSchema.builder("A table.").column("births", ColumnType.INTEGER, Aggregation.SUM, " "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("births");
    }
}
