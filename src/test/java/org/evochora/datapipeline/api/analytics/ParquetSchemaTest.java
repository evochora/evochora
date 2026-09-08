package org.evochora.datapipeline.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ParquetSchema}, in particular for what a column says about the way it
 * reaches a coarser level of detail.
 */
@Tag("unit")
class ParquetSchemaTest {

    @Test
    void aColumnIsSampledUnlessItSaysOtherwise() {
        ParquetSchema schema = ParquetSchema.builder()
            .column("tick", ColumnType.BIGINT)
            .column("avg_energy", ColumnType.DOUBLE)
            .build();

        assertThat(schema.getColumns())
            .extracting(ParquetSchema.Column::aggregation)
            .containsOnly(Aggregation.SAMPLE);
        assertThat(schema.summedColumnIndexes()).isEmpty();
    }

    @Test
    void aColumnCarriesTheAggregationItWasDeclaredWith() {
        ParquetSchema schema = ParquetSchema.builder()
            .column("tick", ColumnType.BIGINT)
            .column("births", ColumnType.INTEGER, Aggregation.SUM)
            .column("alive", ColumnType.INTEGER)
            .column("deaths", ColumnType.BIGINT, Aggregation.SUM)
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
        ParquetSchema schema = ParquetSchema.builder()
            .column("tick", ColumnType.BIGINT)
            .column("births", ColumnType.INTEGER, Aggregation.SUM)
            .column("alive", ColumnType.INTEGER)
            .column("deaths", ColumnType.BIGINT, Aggregation.SUM)
            .build();

        assertThat(schema.summedColumnIndexes()).containsExactly(1, 3);
    }

    @Test
    void aColumnThatCannotBeAddedCannotBeSummed() {
        // Adding the averages of a window produces a number nothing measures, and adding text or
        // a flag produces nothing at all
        assertThatThrownBy(() -> ParquetSchema.builder()
                .column("avg_energy", ColumnType.DOUBLE, Aggregation.SUM))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("avg_energy")
            .hasMessageContaining("INTEGER or BIGINT");

        assertThatThrownBy(() -> ParquetSchema.builder()
                .column("kind", ColumnType.VARCHAR, Aggregation.SUM))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("kind");

        assertThatThrownBy(() -> ParquetSchema.builder()
                .column("is_dead", ColumnType.BOOLEAN, Aggregation.SUM))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("is_dead");
    }

    @Test
    void bothWholeNumberTypesCanBeSummed() {
        ParquetSchema schema = ParquetSchema.builder()
            .column("births", ColumnType.INTEGER, Aggregation.SUM)
            .column("energy_spent", ColumnType.BIGINT, Aggregation.SUM)
            .build();

        assertThat(schema.summedColumnIndexes()).containsExactly(0, 1);
    }

    @Test
    void aColumnWithoutAnAggregationIsNoColumn() {
        assertThatThrownBy(() -> ParquetSchema.builder().column("births", ColumnType.INTEGER, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("aggregation");
    }

    @Test
    void theTableTheSchemaCreatesCarriesTypesRegardlessOfAggregation() {
        ParquetSchema schema = ParquetSchema.builder()
            .column("tick", ColumnType.BIGINT)
            .column("births", ColumnType.INTEGER, Aggregation.SUM)
            .build();

        assertThat(schema.toCreateTableSql("metric_lod0"))
            .isEqualTo("CREATE TABLE metric_lod0 (tick BIGINT, births INTEGER)");
        assertThat(schema.toInsertSql("metric_lod0"))
            .isEqualTo("INSERT INTO metric_lod0 VALUES (?, ?)");
    }
}
