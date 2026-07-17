package com.loeffler.bpmcoach.desktop.ui;

import com.loeffler.bpmcoach.desktop.transport.BandDiscovery;
import com.loeffler.bpmcoach.domain.Student;
import com.loeffler.bpmcoach.persistence.RosterStore;
import com.loeffler.bpmcoach.session.ClassSession;
import com.loeffler.bpmcoach.session.ClassSnapshot;
import com.loeffler.bpmcoach.transport.DiscoveredDevice;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Scan for bands, pick one, name it. The pairing (band address -> student) is written through
 * {@link RosterStore} immediately, so the next launch recognizes the same band without re-pairing -
 * {@link com.loeffler.bpmcoach.desktop.MainApp} loads it back into {@link ClassSession} at startup
 * and {@link BandDiscovery} re-scans to see whether it's currently in range.
 */
public final class PairingView extends SplitPane {

  private final ClassSession session;
  private final RosterStore rosterStore;
  private final ListView<DiscoveredDevice> discoveredList = new ListView<>();
  private final ListView<Student> pairedList = new ListView<>();
  private final TextField nameField = new TextField();
  private final Label status = new Label();

  public PairingView(ClassSession session, BandDiscovery discovery, RosterStore rosterStore) {
    this.session = session;
    this.rosterStore = rosterStore;
    setOrientation(Orientation.HORIZONTAL);
    getStyleClass().add("pairing-view");

    discoveredList.getItems().setAll(discovery.knownDevices().values());
    discoveredList.setCellFactory(
        list ->
            new ListCell<DiscoveredDevice>() {
              @Override
              protected void updateItem(DiscoveredDevice device, boolean empty) {
                super.updateItem(device, empty);
                setText(
                    empty || device == null
                        ? null
                        : "%s  (%s, %d dBm)"
                            .formatted(device.name(), device.address(), device.rssi()));
              }
            });
    discovery
        .updates()
        .subscribe(
            new Flow.Subscriber<DiscoveredDevice>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(DiscoveredDevice device) {
                Platform.runLater(
                    () -> {
                      // Update in place if already listed, so a re-discovered band (this fires
                      // on every scan cycle, every ~6s) refreshes its rssi/name without jumping
                      // to the end of the list and losing the user's place mid-selection.
                      List<DiscoveredDevice> items = discoveredList.getItems();
                      for (int i = 0; i < items.size(); i++) {
                        if (items.get(i).address().equals(device.address())) {
                          items.set(i, device);
                          return;
                        }
                      }
                      items.add(device);
                    });
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });

    pairedList.setCellFactory(
        list ->
            new ListCell<Student>() {
              @Override
              protected void updateItem(Student student, boolean empty) {
                super.updateItem(student, empty);
                setText(
                    empty || student == null
                        ? null
                        : "%s  ->  %s".formatted(student.name(), student.assignedBandAddress()));
              }
            });
    refreshPairedList();
    session
        .updates()
        .subscribe(
            new Flow.Subscriber<ClassSnapshot>() {
              private Flow.Subscription subscription;

              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(1);
              }

              @Override
              public void onNext(ClassSnapshot snapshot) {
                Platform.runLater(PairingView.this::refreshPairedList);
                subscription.request(1);
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });

    Button pairButton = new Button("Pair");
    pairButton.setOnAction(event -> pairSelected());
    Button unpairButton = new Button("Unpair");
    unpairButton.setOnAction(event -> unpairSelected());

    VBox left =
        new VBox(
            8,
            new Label("Discovered bands"),
            discoveredList,
            new Label("Name"),
            nameField,
            pairButton,
            status);
    left.setPadding(new Insets(16));

    VBox right = new VBox(8, new Label("Paired students"), pairedList, unpairButton);
    right.setPadding(new Insets(16));

    getItems().addAll(left, right);
  }

  private void pairSelected() {
    DiscoveredDevice device = discoveredList.getSelectionModel().getSelectedItem();
    String name = nameField.getText() == null ? "" : nameField.getText().trim();
    if (device == null) {
      status.setText("Select a discovered band first.");
      return;
    }
    if (name.isEmpty()) {
      status.setText("Enter a name for this band.");
      return;
    }

    session.unassignBand(device.address()); // an address belongs to at most one student
    Student existing =
        session.roster().stream()
            .filter(s -> s.name().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    Student paired =
        existing != null
            ? new Student(existing.id(), existing.name(), device.address())
            : new Student(generateId(name), name, device.address());
    session.upsertStudent(paired);
    rosterStore.save(session.roster());

    status.setText("Paired \"%s\" to %s.".formatted(name, device.address()));
    nameField.clear();
  }

  private void unpairSelected() {
    Student selected = pairedList.getSelectionModel().getSelectedItem();
    if (selected == null) {
      status.setText("Select a paired student first.");
      return;
    }
    session.removeStudent(selected.id());
    rosterStore.save(session.roster());
    status.setText("Removed " + selected.name() + ".");
  }

  private void refreshPairedList() {
    List<Student> paired = session.roster().stream().filter(Student::hasBand).toList();
    pairedList.getItems().setAll(paired);
  }

  private String generateId(String name) {
    Set<String> existingIds =
        session.roster().stream().map(Student::id).collect(Collectors.toSet());
    String base =
        name.trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    if (base.isEmpty()) {
      base = "student";
    }
    String candidate = base;
    int suffix = 2;
    while (existingIds.contains(candidate)) {
      candidate = base + "-" + suffix++;
    }
    return candidate;
  }
}
