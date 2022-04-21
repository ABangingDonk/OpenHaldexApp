package com.kong.openhaldex;

import java.io.Serializable;
import java.util.ArrayList;

public class Mode implements Serializable {
    String name;
    ArrayList<LockPoint> lockPoints = new ArrayList<LockPoint>();
    boolean editable;
    byte id;

    public Mode(){ }

    public Mode(String _name, ArrayList<LockPoint> _lockPoints, boolean _editable, byte _id){
        name = _name;
        lockPoints = _lockPoints;
        editable = _editable;
        id = _id;
    }
}
