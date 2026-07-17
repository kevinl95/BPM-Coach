package com.loeffler.bpmcoach.desktop.ui;

import com.loeffler.bpmcoach.session.StudentStatus;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** One student's tile: name + live BPM, color-coded by zone. Sized to read from across a gym. */
final class StudentTileView extends VBox {

  private final Label nameLabel = new Label();
  private final Label bpmLabel = new Label();
  private String currentZoneStyleClass;

  StudentTileView() {
    getStyleClass().add("zone-tile");
    setAlignment(Pos.CENTER);
    setPrefSize(220, 160);
    nameLabel.getStyleClass().add("tile-name");
    bpmLabel.getStyleClass().add("tile-bpm");
    getChildren().addAll(nameLabel, bpmLabel);
  }

  void update(StudentStatus status) {
    nameLabel.setText(status.student().name());
    bpmLabel.setText(status.bpm().isPresent() ? status.bpm().getAsInt() + " bpm" : "--");

    String zoneStyleClass = ZoneColors.styleClass(status.zone());
    if (!zoneStyleClass.equals(currentZoneStyleClass)) {
      if (currentZoneStyleClass != null) {
        getStyleClass().remove(currentZoneStyleClass);
      }
      getStyleClass().add(zoneStyleClass);
      currentZoneStyleClass = zoneStyleClass;
    }
  }
}
