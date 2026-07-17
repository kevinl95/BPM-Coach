package com.loeffler.bpmcoach.desktop.ui;

import com.loeffler.bpmcoach.domain.ZoneConfig;
import com.loeffler.bpmcoach.session.ClassSession;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;

/** Lets the teacher tune the BPM thresholds that separate LOW/TARGET/HIGH zones. */
public final class ZoneConfigView extends GridPane {

  public ZoneConfigView(ClassSession session) {
    getStyleClass().add("zone-config-view");
    setHgap(12);
    setVgap(12);
    setPadding(new Insets(24));

    ZoneConfig current = session.zoneConfig();
    Spinner<Integer> lowMaxSpinner =
        new Spinner<>(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(40, 220, current.lowMax()));
    Spinner<Integer> targetMaxSpinner =
        new Spinner<>(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(40, 220, current.targetMax()));
    lowMaxSpinner.setEditable(true);
    targetMaxSpinner.setEditable(true);

    Label status = new Label();
    Button apply = new Button("Apply");
    apply.setOnAction(
        event -> {
          try {
            session.updateZoneConfig(
                new ZoneConfig(lowMaxSpinner.getValue(), targetMaxSpinner.getValue()));
            status.setText("Applied.");
          } catch (IllegalArgumentException e) {
            status.setText(e.getMessage());
          }
        });

    addRow(0, new Label("Yellow / Green boundary (bpm)"), lowMaxSpinner);
    addRow(1, new Label("Green / Red boundary (bpm)"), targetMaxSpinner);
    add(apply, 1, 2);
    add(status, 0, 3, 2, 1);
  }
}
