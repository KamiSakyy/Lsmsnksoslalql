package com.tsuyu.messenger.ui.screens;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.tsuyu.messenger.MainActivity;

/**
 * Экран приложения как отдельный фрагмент.
 *
 * <p>Раньше все экраны жили внутри одного класса MainActivity: она сама раздувала разметку,
 * держала состояние каждого экрана и вручную снимала/ставила подписки. Теперь у каждого
 * экрана свой класс со своим жизненным циклом, а MainActivity только переключает их и
 * связывает с данными. Это и есть разделение «один экран — один класс».
 */
public abstract class TsuyuScreen extends Fragment {

    private static final String ARG_LAYOUT = "screen_layout";

    /** Имя экрана — то же, что раньше хранилось в поле page. */
    @NonNull public abstract String pageName();

    /** Разметка экрана. */
    @LayoutRes protected abstract int layoutResource();

    /** Разметка экрана (для сверки, тот же ли экран уже показан). */
    @LayoutRes public final int layoutId() { return layoutResource(); }

    /** Имя экрана для остального кода приложения. */
    @NonNull public final String page() { return pageName(); }

    /** Что делать, когда экран впервые показан (обычно — подписки и первичная отрисовка). */
    public void onScreenShown(@NonNull View root) { }

    /** Что делать, когда экран уходит: снять подписки, остановить обновления. */
    public void onScreenHidden() { }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(layoutResource(), container, false);
    }

    /** Активность-хозяин: через неё экран получает данные и обработчики. */
    @Nullable
    protected MainActivity host() {
        return getActivity() instanceof MainActivity ? (MainActivity) getActivity() : null;
    }

    /** Служебная разметка для случаев, когда экран создаётся из кода. */
    public static Bundle arguments(@LayoutRes int layout) {
        Bundle bundle = new Bundle();
        bundle.putInt(ARG_LAYOUT, layout);
        return bundle;
    }

    /** Тег фрагмента по разметке: по нему MainActivity находит созданный экран. */
    @NonNull public static String tagFor(@LayoutRes int layoutResource) {
        return "tsuyu-screen-" + layoutResource;
    }
}
