package com.loeffler.bpmcoach.desktop.ui;

import com.loeffler.bpmcoach.domain.HeartRateReading;
import com.loeffler.bpmcoach.domain.Student;
import com.loeffler.bpmcoach.session.ClassSession;
import com.loeffler.bpmcoach.session.ClassSnapshot;
import com.loeffler.bpmcoach.session.StudentStatus;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Flow;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.VBox;

/** Per-student history: pick a student, see their readings, most recent first. */
public final class HistoryView extends SplitPane {

  private static final DateTimeFormatter TIME_FORMAT =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

  private final ClassSession session;
  private final ListView<Student> studentList = new ListView<>();
  private final ListView<String> readingList = new ListView<>();

  public HistoryView(ClassSession session) {
    this.session = session;
    setOrientation(Orientation.HORIZONTAL);
    getStyleClass().add("history-view");

    studentList.setCellFactory(
        list ->
            new ListCell<>() {
              @Override
              protected void updateItem(Student student, boolean empty) {
                super.updateItem(student, empty);
                setText(empty || student == null ? null : student.name());
              }
            });
    studentList
        .getSelectionModel()
        .selectedItemProperty()
        .addListener((observable, oldValue, selected) -> refresh(selected));

    List<Student> students =
        session.currentSnapshot().statuses().stream().map(StudentStatus::student).toList();
    studentList.getItems().setAll(students);

    VBox left = new VBox(8, new Label("Students"), studentList);
    VBox right = new VBox(8, new Label("Recent readings (most recent first)"), readingList);
    getItems().addAll(left, right);

    if (!students.isEmpty()) {
      studentList.getSelectionModel().selectFirst();
    }

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
                Platform.runLater(() -> refresh(studentList.getSelectionModel().getSelectedItem()));
                subscription.request(1);
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });
  }

  private void refresh(Student student) {
    if (student == null) {
      readingList.getItems().clear();
      return;
    }
    List<String> lines =
        session.historyFor(student.id()).stream().map(HistoryView::format).toList();
    readingList.getItems().setAll(lines);
  }

  private static String format(HeartRateReading reading) {
    String bpm = reading.bpm().isPresent() ? reading.bpm().getAsInt() + " bpm" : "no reading";
    return "%s  %s".formatted(TIME_FORMAT.format(reading.timestamp()), bpm);
  }
}
