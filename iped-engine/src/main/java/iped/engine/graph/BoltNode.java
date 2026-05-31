package iped.engine.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterable;

/**
 * Read-only {@link Node} snapshot backed by a Neo4j Bolt driver node. See {@link BoltEntity}.
 */
public class BoltNode extends BoltEntity implements Node {

    private final List<Label> labels;

    public BoltNode(long id, String elementId, Iterable<String> labelNames, Map<String, Object> properties) {
        super(id, elementId, properties);
        this.labels = new ArrayList<>();
        if (labelNames != null) {
            for (String name : labelNames) {
                labels.add(DynLabel.label(name));
            }
        }
    }

    static BoltNode from(org.neo4j.driver.types.Node node) {
        return new BoltNode(node.id(), node.elementId(), node.labels(), node.asMap());
    }

    @Override
    public Iterable<Label> getLabels() {
        return labels;
    }

    @Override
    public boolean hasLabel(Label label) {
        for (Label l : labels) {
            if (l.name().equals(label.name())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "BoltNode[" + id + "]";
    }

    // --- live traversal / mutation: unsupported on detached Bolt snapshots ---

    @Override
    public ResourceIterable<Relationship> getRelationships() {
        throw unsupported();
    }

    @Override
    public boolean hasRelationship() {
        throw unsupported();
    }

    @Override
    public ResourceIterable<Relationship> getRelationships(RelationshipType... types) {
        throw unsupported();
    }

    @Override
    public ResourceIterable<Relationship> getRelationships(Direction direction, RelationshipType... types) {
        throw unsupported();
    }

    @Override
    public boolean hasRelationship(RelationshipType... types) {
        throw unsupported();
    }

    @Override
    public boolean hasRelationship(Direction direction, RelationshipType... types) {
        throw unsupported();
    }

    @Override
    public ResourceIterable<Relationship> getRelationships(Direction direction) {
        throw unsupported();
    }

    @Override
    public boolean hasRelationship(Direction direction) {
        throw unsupported();
    }

    @Override
    public Relationship getSingleRelationship(RelationshipType type, Direction direction) {
        throw unsupported();
    }

    @Override
    public Relationship createRelationshipTo(Node otherNode, RelationshipType type) {
        throw unsupported();
    }

    @Override
    public Iterable<RelationshipType> getRelationshipTypes() {
        throw unsupported();
    }

    @Override
    public int getDegree() {
        throw unsupported();
    }

    @Override
    public int getDegree(RelationshipType type) {
        throw unsupported();
    }

    @Override
    public int getDegree(Direction direction) {
        throw unsupported();
    }

    @Override
    public int getDegree(RelationshipType type, Direction direction) {
        throw unsupported();
    }

    @Override
    public void addLabel(Label label) {
        throw unsupported();
    }

    @Override
    public void removeLabel(Label label) {
        throw unsupported();
    }
}
