#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Execute the production geometry against lightweight view state doubles."""
from pathlib import Path
import subprocess
import tempfile
root=Path(__file__).resolve().parents[1]
work=Path(tempfile.mkdtemp(prefix='lockscreen-geometry-',dir='/tmp'))
sources={
'android/view/View.java': '''package android.view;
public class View {
 public int left,top,width,height; public float sx=1,sy=1,tx,ty,px,py;
 public int getLeft(){return left;} public int getTop(){return top;}
 public int getWidth(){return width;} public int getHeight(){return height;}
 public void setScaleX(float v){sx=v;} public void setScaleY(float v){sy=v;}
 public void setTranslationX(float v){tx=v;} public void setTranslationY(float v){ty=v;}
 public void setPivotX(float v){px=v;} public void setPivotY(float v){py=v;}
}''',
'android/view/ViewGroup.java': '''package android.view;
public class ViewGroup extends View {
 public View[] children; public int getChildCount(){return children.length;}
 public View getChildAt(int i){return children[i];}
}''',
'GeometryTest.java': '''import android.view.*;
import io.mesalabs.unica.lockscreen.ResizeGeometry;
import com.samsung.android.sdk.aodservice.entities.item.LockScreenItem;
public class GeometryTest {
 static void equal(float a,float b){if(Math.abs(a-b)>.001f)throw new AssertionError(a+" != "+b);}
 public static void main(String[] args){
  for(float x:new float[]{.5f,.75f,1f,1.25f}) for(float y:new float[]{.5f,.75f,1f,1.25f}) {
   ViewGroup root=new ViewGroup();root.width=ResizeGeometry.extent(400,x);root.height=ResizeGeometry.extent(200,y);
   root.children=new View[8];
   for(int i=0;i<8;i++){View c=new View();c.left=i%4*100;c.top=i/4*100;c.width=c.height=100;root.children[i]=c;}
   ResizeGeometry.layout(root,400,200,x,y);
   float minX=999,minY=999,maxX=0,maxY=0;
   for(View c:root.children){
    equal(c.sx,c.sy);equal(c.width*c.sx/(c.height*c.sy),1);
    float l=c.left+c.tx,t=c.top+c.ty,r=l+c.width*c.sx,b=t+c.height*c.sy;
    if(l<-.01||t<-.01||r>root.width+.01||b>root.height+.01)throw new AssertionError("clipped child");
    minX=Math.min(minX,l);minY=Math.min(minY,t);maxX=Math.max(maxX,r);maxY=Math.max(maxY,b);
   }
   equal(minX,root.width-maxX);equal(minY,root.height-maxY);
   float before=root.children[7].tx;ResizeGeometry.layout(root,400,200,x,y);equal(before,root.children[7].tx);
  }
  equal(ResizeGeometry.factor(Float.NaN),1);equal(ResizeGeometry.factor(Float.POSITIVE_INFINITY),1);
  equal(ResizeGeometry.factor(0),1);equal(ResizeGeometry.factor(-1),1);
  equal(ResizeGeometry.factor(.1f),.5f);equal(ResizeGeometry.factor(10),1.25f);
  LockScreenItem item=new LockScreenItem(){public float getScaleX(){return .75f;}public float getScaleY(){return 1.25f;}};
  equal(ResizeGeometry.extentX(400,item),300);equal(ResizeGeometry.extentY(200,item),250);
  equal(ResizeGeometry.extentX(0,item),0);equal(ResizeGeometry.extentY(200,null),200);
  System.out.println("PASS: eight-cell aspect ratios, containment, centering, repeated layout, bounds and invalid factors");
 }
}'''
}
for name,source in sources.items():
 p=work/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
inputs=list(work.rglob('*.java'))+[root/'java/io/mesalabs/unica/lockscreen/ResizeGeometry.java',root/'stubs/com/samsung/android/sdk/aodservice/entities/item/LockScreenItem.java']
subprocess.run(['javac','-d',str(work/'classes'),*[str(p) for p in inputs]],check=True)
subprocess.run(['java','-cp',str(work/'classes'),'GeometryTest'],check=True)
