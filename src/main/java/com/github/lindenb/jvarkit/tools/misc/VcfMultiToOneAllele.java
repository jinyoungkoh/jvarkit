/*
The MIT License (MIT)

Copyright (c) 2015 Pierre Lindenbaum

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


History:
* 2015 creation

*/
package com.github.lindenb.jvarkit.tools.misc;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypeBuilder;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLine;
import htsjdk.variant.vcf.VCFHeaderLineCount;
import htsjdk.variant.vcf.VCFHeaderLineType;
import htsjdk.variant.vcf.VCFInfoHeaderLine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.lindenb.jvarkit.util.picard.SAMSequenceDictionaryProgress;
import com.github.lindenb.jvarkit.util.vcf.PostponedVariantContextWriter;
import com.github.lindenb.jvarkit.util.vcf.VcfIterator;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import com.github.lindenb.jvarkit.util.jcommander.Launcher;
import com.github.lindenb.jvarkit.util.jcommander.Program;
import com.github.lindenb.jvarkit.util.log.Logger;
/*
BEGIN_DOC

## Example

Exac contains multi-ALT  variants:

```bash
$ gunzip -c ExAC.r0.3.sites.vep.vcf.gz | grep rs3828049

1	889238	rs3828049	G	A,C	8422863.10	PASS	AC=6926,3;AC_AFR=220,0;AC_AMR=485,1;AC_Adj=6890,3;AC_EAS=746,0;AC_FIN=259,0;AC_Het=6442,3,0;AC_Hom=224,0;AC_NFE=3856,0;AC_OTH=41,0;AC_SAS=1283,2;AF=0.057,2.472e-05;AN=121358;AN_AFR=10148;AN_AMR=11522;AN_Adj=119272;AN_EAS=8582;AN_FIN=6358;AN_NFE=65282;AN_OTH=876;AN_SAS=16504;(...)

```

processed with this tools:
```
$ java -jar dist/vcfmulti2oneallele.jar  ExAC.r0.3.sites.vep.vcf.gz   | grep rs3828049

1	889238	rs3828049	G	A	8422863.10	PASS	AC=6926;AC_AFR=220;AC_AMR=485;AC_Adj=6890;AC_EAS=746;AC_FIN=259;AC_Het=6442;AC_Hom=224;AC_NFE=3856;AC_OTH=41;AC_SAS=1283;AF=0.057;AN=121358;AN_AFR=10148;AN_AMR=11522;AN_Adj=119272;AN_EAS=8582;AN_FIN=6358;AN_NFE=65282;AN_OTH=876;AN_SAS=16504;BaseQRankSum=-2.170e-01;VCF_MULTIALLELIC_SRC=A|C;(...)
1	889238	rs3828049	G	C	8422863.10	PASS	AC=3;AC_AFR=0;AC_AMR=1;AC_Adj=3;AC_EAS=0;AC_FIN=0;AC_Het=3;AC_Hom=0;AC_NFE=0;AC_OTH=0;AC_SAS=2;AF=2.472e-05;AN=121358;AN_AFR=10148;AN_AMR=11522;AN_Adj=119272;AN_EAS=8582;AN_FIN=6358;AN_NFE=65282;AN_OTH=876;AN_SAS=16504;VCF_MULTIALLELIC_SRC=A|C;(....)
```

## History

* 20170606 added support for VCFHeaderLineCount.R

END_DOC
 */
@Program(
		name="vcfmulti2oneallele",
		description="'one variant with N ALT alleles' to 'N variants with one ALT'",
		keywords={"vcf"}
		)
public class VcfMultiToOneAllele
	extends Launcher
	{
	private static final Logger LOG = Logger.build(VcfMultiToOneAllele.class).make();
	@Parameter(names={"-o","--output"},description=OPT_OUPUT_FILE_OR_STDOUT)
	private File outputFile = null;
	@Parameter(names={"-p","--samples"},description="print sample name. set genotype to ./. if both allele of the genotype are in 'ALT'")
	private boolean print_samples = false;
	@Parameter(names={"-r","--rmAtt"},description="20161110: after merging with GATK CombineVariants there can have problemes with INFO/type='A' present in vcf1 but not in vcf2, and multiallelelic variants. This option delete the attributes having such problems.")
	private boolean rmErrorAttributes = false;
	@ParametersDelegate
	private PostponedVariantContextWriter.WritingVcfConfig writingVcfArgs = new PostponedVariantContextWriter.WritingVcfConfig();

	
	 public VcfMultiToOneAllele()
		{
		}
	 
	@Override
	/* public for knime */
	public int doVcfToVcf(String inputName,
			VcfIterator in, VariantContextWriter out)   {
			try {
			final String TAG="VCF_MULTIALLELIC_SRC";
			final List<String> noSamples=Collections.emptyList();
		
			final VCFHeader header=in.getHeader();
			final List<String> sample_names=header.getSampleNamesInOrder();
			final Set<VCFHeaderLine> metaData=new HashSet<>(header.getMetaDataInInputOrder());
			//addMetaData(metaData);		
			metaData.add(new VCFInfoHeaderLine(TAG, 1, VCFHeaderLineType.String,
					"The variant was processed with VcfMultiAlleleToOneAllele and contained the following alleles."));
			VCFHeader h2;
			
			if(!this.print_samples)
				{
				h2 = new VCFHeader(
						metaData,
						noSamples
						);
				}
			else
				{
				h2 = new VCFHeader(
						metaData,
						sample_names
						);
				}
			SAMSequenceDictionaryProgress progess=new SAMSequenceDictionaryProgress(header);
			out.writeHeader(h2);
			while(in.hasNext())
				{
				final VariantContext ctx=progess.watch(in.next());
				final List<Allele> alternateAlleles = new ArrayList<>(ctx.getAlternateAlleles());
				if(alternateAlleles.isEmpty())
					{
					LOG.warn("Remove no ALT variant:"+ctx);
					continue;
					}
				else if(alternateAlleles.size()==1)
					{
					if(!print_samples)
						{
						final VariantContextBuilder vcb = new VariantContextBuilder(ctx);
						vcb.noGenotypes();
						out.add(vcb.make());
						}
					else
						{
						out.add(ctx);
						}
					}
				else
					{
					//Collections.sort(aioulleles); don't sort , for VCFHeaderLineCount.A
					final Map<String,Object> attributes = ctx.getAttributes();
					final StringBuilder sb=new StringBuilder();
					for(int i=0;i< alternateAlleles.size();++i)
						{
						if(sb.length()>0) sb.append("|");
						sb.append(alternateAlleles.get(i).getDisplayString());
						}
					final String altAsString= sb.toString();
					for(int alternateIndex=0;alternateIndex< alternateAlleles.size();++alternateIndex)
						{
						final Allele the_allele = alternateAlleles.get(alternateIndex);
	
						final VariantContextBuilder vcb = new VariantContextBuilder(ctx);
						vcb.alleles(Arrays.asList(ctx.getReference(),the_allele));
						
						for(final String attid:attributes.keySet())
							{
							final VCFInfoHeaderLine info = header.getInfoHeaderLine(attid);
							if(info==null) throw new IOException("Cannot get header INFO tag="+attid);
							final VCFHeaderLineCount lineCount = info.getCountType();
							
							if(lineCount!=VCFHeaderLineCount.A && lineCount!=VCFHeaderLineCount.R) continue;
							final Object o = 	attributes.get(attid);
							
							if(!(o instanceof List)) {
								final String msg="For INFO tag="+attid+" got "+o.getClass()+" instead of List in "+ctx;
								if(this.rmErrorAttributes)
									{
									LOG.warn("remove this attribute : "+msg);
									vcb.rmAttribute(attid);
									continue;
									}
								else
									{
									throw new IOException(msg);
									}				
								}
							@SuppressWarnings("rawtypes")
							final List list = (List)o;
							// if 'R' we expected alleles.size()+list.size()+1 (for ref allele)
							final int mallusForRef = +(lineCount.equals(VCFHeaderLineCount.A)?0:1);
							if(alternateAlleles.size() + mallusForRef !=list.size()) {
								final String msg= ctx.getContig()+":"+ctx.getStart()+" : For INFO tag="+attid+" got "+alternateAlleles.size()+" ALT, incompatible with "+list.toString();
								if(this.rmErrorAttributes)
									{
									LOG.warn("remove this attribute : "+msg);
									vcb.rmAttribute(attid);
									continue;
									}
								else
									{
									throw new IOException(msg);
									}
								}
							else if(lineCount.equals(VCFHeaderLineCount.R)) {
								final List<Object> rlist = new ArrayList<>(2);
								rlist.add(list.get(0));///0==REF
								rlist.add(list.get(alternateIndex+1));
								vcb.attribute(attid, rlist);	
								}
							else // VCFHeaderLineCount.A
								{	
								vcb.attribute(attid, list.get(alternateIndex));	
								}
							}
						
						vcb.attribute(TAG,altAsString);
						
						if(!print_samples)
							{
							vcb.noGenotypes();
							}
						else
							{
							final List<Genotype> genotypes=new ArrayList<>(sample_names.size());
							
							for(final String sampleName: sample_names)
								{							
								final Genotype g= ctx.getGenotype(sampleName);
								if(!g.isCalled() || g.isNoCall() )
									{
									genotypes.add(g);
									continue;
									}
								
								
								final GenotypeBuilder gb =new GenotypeBuilder(g);
								final List<Allele> galist = new ArrayList<>(g.getAlleles());
								
								if(galist.size()>0)
									{
									boolean replace=false;
									for(int y=0;y< galist.size();++y)
										{
										final Allele ga = galist.get(y);
										if(ga.isSymbolic()) throw new RuntimeException("How should I handle "+ga);
										if(!(ga.isNoCall() || 
											 ga.equals(ctx.getReference()) ||
											 ga.equals(the_allele)))
											{
											replace=true;
											galist.set(y, ctx.getReference());
											}
										}
									if(replace)
										{
										gb.reset(true);/* keep sample name */
										gb.alleles(galist);
										}
									}
								genotypes.add(gb.make());
								}
							
							vcb.genotypes(genotypes);
							}
						
						out.add(vcb.make());
						}
					}
				}
			progess.finish();
			return RETURN_OK;
			} 
		catch(Exception err) {
			LOG.error(err);
			return -1;
			}
		}

	@Override
	public int doWork(final List<String> args) {
		return doVcfToVcf(args,outputFile);
		}
	
	@Override
	protected VariantContextWriter openVariantContextWriter(final File outorNull) throws IOException {
		return new PostponedVariantContextWriter(this.writingVcfArgs,stdout(),this.outputFile);
		}

	
	public static void main(final String[] args)
		{
		new VcfMultiToOneAllele().instanceMainWithExit(args);
		}
	
	}
