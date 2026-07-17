package com.loeffler.bpmcoach.desktop.ui;

import com.loeffler.bpmcoach.session.ClassSession;
import com.loeffler.bpmcoach.session.ClassSnapshot;
import com.loeffler.bpmcoach.session.StudentStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Flow;
import javafx.application.Platform;
import javafx.scene.layout.FlowPane;

/**
 * The projector/presentation view: one large color-coded tile per student, live-updating.
 * Subscribes to {@link ClassSession#updates()} (a stdlib {@link Flow.Publisher}, no JavaFX
 * dependency in core) and marshals every update onto the FX Application Thread.
 */
public final class ProjectorView extends FlowPane {

  private final Map<String, StudentTileView> tiles = new LinkedHashMap<>();

  public ProjectorView(ClassSession session) {
    getStyleClass().add("projector-view");
    setHgap(16);
    setVgap(16);

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
                Platform.runLater(() -> render(snapshot));
                subscription.request(1);
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });

    render(session.currentSnapshot());
  }

  private void render(ClassSnapshot snapshot) {
    for (StudentStatus status : snapshot.statuses()) {
      StudentTileView tile =
          tiles.computeIfAbsent(
              status.student().id(),
              id -> {
                StudentTileView created = new StudentTileView();
                getChildren().add(created);
                return created;
              });
      tile.update(status);
    }
  }
}
