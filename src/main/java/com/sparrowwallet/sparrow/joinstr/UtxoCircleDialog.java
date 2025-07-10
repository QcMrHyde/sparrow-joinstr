package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.sparrow.AppServices;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.paint.Color;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.paint.CycleMethod;
import javafx.scene.input.MouseEvent;
import javafx.geometry.Insets;
import javafx.scene.text.Text;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;

public class UtxoCircleDialog extends Dialog<Void> {
    private static final double MIN_RADIUS = 30;
    private static final double MAX_RADIUS = 120;
    private static final double REPULSION_STRENGTH = 500;
    private static final double DAMPING = 0.95;
    private static final double MIN_DISTANCE = 5;

    private final List<UtxoBubble> bubbles = new ArrayList<>();
    private final Pane canvas;
    private final AnimationTimer physics;
    private final Set<UtxoBubble> selectedBubbles = new HashSet<>();

    private static class UtxoBubble {
        final BlockTransactionHashIndex utxo;
        final WalletNode node;
        final Circle circle;
        final Text text;
        final StackPane container;
        final double radius;
        final long amount;
        UtxoCircleDialog dialog;

        double x, y;
        double vx, vy;
        boolean selected = false;

        UtxoBubble(BlockTransactionHashIndex utxo, WalletNode node, double radius, double canvasWidth, double canvasHeight, UtxoCircleDialog dialog) {
            this.utxo = utxo;
            this.node = node;
            this.radius = radius;
            this.amount = utxo.getValue();
            this.dialog = dialog;

            // Random initial position
            this.x = ThreadLocalRandom.current().nextDouble(radius, canvasWidth - radius);
            this.y = ThreadLocalRandom.current().nextDouble(radius, canvasHeight - radius);
            this.vx = ThreadLocalRandom.current().nextDouble(-2, 2);
            this.vy = ThreadLocalRandom.current().nextDouble(-2, 2);

            // Create visual elements
            this.circle = new Circle(radius);
            this.container = new StackPane();

            // Create amount text
            String amountBtc = String.format("%.4f", amount / 100000000.0);
            this.text = new Text(amountBtc);
            this.text.setFont(Font.font("Arial", FontWeight.BOLD, Math.max(8, radius / 4)));
            this.text.setFill(Color.WHITE);

            this.container.getChildren().addAll(circle, text);

            // Set initial visual state
            updateVisualState();
            setupInteractions();
        }

        private void setupInteractions() {
            // Click to select/deselect
            container.setOnMouseClicked(e -> {
                selected = !selected;

                if (selected) {
                    dialog.selectedBubbles.add(this);
                } else {
                    dialog.selectedBubbles.remove(this);
                }

                updateVisualState();
            });

            // Hover effects
            container.setOnMouseEntered(e -> {
                if (!selected) {
                    circle.setStrokeWidth(3);
                    ScaleTransition scale = new ScaleTransition(Duration.millis(150), container);
                    scale.setToX(1.1);
                    scale.setToY(1.1);
                    scale.play();
                }
            });

            container.setOnMouseExited(e -> {
                if (!selected) {
                    circle.setStrokeWidth(2);
                    ScaleTransition scale = new ScaleTransition(Duration.millis(150), container);
                    scale.setToX(1.0);
                    scale.setToY(1.0);
                    scale.play();
                }
            });

            // Tooltip
            Tooltip tooltip = new Tooltip(
                    String.format("Amount: %.8f BTC (%,d sats)\nTXID: %s\nVOUT: %d",
                            amount / 100000000.0, amount, utxo.getHash(), utxo.getIndex())
            );
            tooltip.setShowDelay(Duration.millis(300));
            Tooltip.install(container, tooltip);
        }

        private void updateVisualState() {
            if (selected) {
                // Selected state - solid bright green with yellow outline
                circle.setFill(Color.web("#2596be"));
                circle.setStrokeWidth(3);
                text.setFill(Color.WHITE);

                ScaleTransition scale = new ScaleTransition(Duration.millis(200), container);
                scale.setToX(1.2);
                scale.setToY(1.2);
                scale.play();
            } else {
                // Unselected state - transparent gradient
                RadialGradient gradient = new RadialGradient(
                        0, 0, 0.5, 0.5, 0.8, true, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.WHITE.deriveColor(0, 1, 1, 0.1)),
                        new Stop(0.7, Color.LIGHTBLUE.deriveColor(0, 1, 1, 0.2)),
                        new Stop(1, Color.DARKBLUE.deriveColor(0, 1, 1, 0.3))
                );
                circle.setFill(gradient);
                circle.setStroke(Color.STEELBLUE.deriveColor(0, 1, 1, 0.6));
                circle.setStrokeWidth(2);
                text.setFill(Color.WHITE);

                ScaleTransition scale = new ScaleTransition(Duration.millis(200), container);
                scale.setToX(1.0);
                scale.setToY(1.0);
                scale.play();
            }
        }

        void updatePosition(double canvasWidth, double canvasHeight) {
            // Apply forces and update position
            vx *= DAMPING;
            vy *= DAMPING;

            x += vx;
            y += vy;

            // Boundary collision
            if (x - radius < 0) {
                x = radius;
                vx = Math.abs(vx);
            } else if (x + radius > canvasWidth) {
                x = canvasWidth - radius;
                vx = -Math.abs(vx);
            }

            if (y - radius < 0) {
                y = radius;
                vy = Math.abs(vy);
            } else if (y + radius > canvasHeight) {
                y = canvasHeight - radius;
                vy = -Math.abs(vy);
            }

            // Update visual position
            container.setLayoutX(x - radius);
            container.setLayoutY(y - radius);
        }

        void applyRepulsion(UtxoBubble other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double distance = Math.sqrt(dx * dx + dy * dy);

            if (distance > 0 && distance < radius + other.radius + MIN_DISTANCE) {
                double overlap = radius + other.radius + MIN_DISTANCE - distance;
                double force = overlap * REPULSION_STRENGTH / (distance * distance);

                double fx = (dx / distance) * force;
                double fy = (dy / distance) * force;

                vx += fx / radius;
                vy += fy / radius;
                other.vx -= fx / other.radius;
                other.vy -= fy / other.radius;
            }
        }
    }

    public UtxoCircleDialog(Wallet wallet) {
        final DialogPane dialogPane = getDialogPane();
        dialogPane.getStylesheets().add(AppServices.class.getResource("general.css").toExternalForm());
        AppServices.setStageIcon(dialogPane.getScene().getWindow());

        // Create canvas
        canvas = new Pane();
        double canvasWidth = 800;
        double canvasHeight = 600;
        canvas.setPrefSize(canvasWidth, canvasHeight);

        // Get UTXOs and create bubbles
        Map<BlockTransactionHashIndex, WalletNode> utxos = wallet.getWalletUtxos();

        if (!utxos.isEmpty()) {
            // Calculate radius scaling
            long minAmount = utxos.keySet().stream().mapToLong(BlockTransactionHashIndex::getValue).min().orElse(0);
            long maxAmount = utxos.keySet().stream().mapToLong(BlockTransactionHashIndex::getValue).max().orElse(0);

            for (Map.Entry<BlockTransactionHashIndex, WalletNode> entry : utxos.entrySet()) {
                BlockTransactionHashIndex utxo = entry.getKey();
                WalletNode node = entry.getValue();

                double radius = calculateRadius(utxo.getValue(), minAmount, maxAmount);
                UtxoBubble bubble = new UtxoBubble(utxo, node, radius, canvasWidth, canvasHeight, this);

                bubbles.add(bubble);
                canvas.getChildren().add(bubble.container);
            }
        }

        // Physics animation
        physics = new AnimationTimer() {
            @Override
            public void handle(long now) {
                // Apply repulsion between bubbles
                for (int i = 0; i < bubbles.size(); i++) {
                    for (int j = i + 1; j < bubbles.size(); j++) {
                        bubbles.get(i).applyRepulsion(bubbles.get(j));
                    }
                }

                // Update positions
                for (UtxoBubble bubble : bubbles) {
                    bubble.updatePosition(canvasWidth, canvasHeight);
                }
            }
        };
        physics.start();

        // Wrap in scroll pane
        ScrollPane scrollPane = new ScrollPane(canvas);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setPrefViewportHeight(600);
        scrollPane.setPrefViewportWidth(800);
        scrollPane.setStyle("-fx-background-color: transparent;");

        dialogPane.setContent(scrollPane);

        // Dialog buttons - only Register and Cancel
        ButtonType registerButtonType = new ButtonType("Register", ButtonBar.ButtonData.OK_DONE);
        dialogPane.getButtonTypes().addAll(registerButtonType, ButtonType.CANCEL);

        // Set dialog properties
        setTitle("UTXO Selection - Interactive Bubble View");
        dialogPane.setPrefSize(850, 700);
        setResizable(true);

        // Cleanup on close
        setOnCloseRequest(e -> physics.stop());

        // Center dialog
        AppServices.moveToActiveWindowScreen(this);
    }

    private double calculateRadius(long amount, long minAmount, long maxAmount) {
        if (maxAmount == minAmount) {
            return (MIN_RADIUS + MAX_RADIUS) / 2;
        }

        // Square root scaling for better visual distribution
        double normalized = Math.sqrt((double)(amount - minAmount) / (maxAmount - minAmount));
        return MIN_RADIUS + (MAX_RADIUS - MIN_RADIUS) * normalized;
    }

    public Set<BlockTransactionHashIndex> getSelectedUtxos() {
        return bubbles.stream()
                .filter(bubble -> bubble.selected)
                .map(bubble -> bubble.utxo)
                .collect(java.util.stream.Collectors.toSet());
    }
}